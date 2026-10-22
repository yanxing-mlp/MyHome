package com.familyhome.file.biz.storage;

import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.file.biz.controller.b.StorageDirectoryController;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Only real invocation-owned TempDir files, a manual queue and a mutable clock. No sleeps, polling,
 * Boot context, database, mount/permission changes or business media. Provider faults below are
 * deliberately injected, not claims of native Windows junction or cross-mount integration coverage.
 */
class StorageDirectoryUsageTest {
    private static final String ERROR = "目录大小统计失败，请稍后重试";
    private static final String MARKER = ".myhome-storage-id";

    @TempDir
    Path temporaryDirectory;
    private Path sandbox;
    private Path publicRoot;
    private Path privateRoot;
    private ManualExecutor executor;
    private MutableClock clock;
    private StorageDirectoryUsage usage;
    private AtomicInteger validations;
    private StorageDirectoryUsage.RootValidation validation;

    @BeforeEach
    void setUp() throws IOException {
        sandbox = temporaryDirectory.toRealPath();
        publicRoot = Files.createDirectory(sandbox.resolve("public"));
        privateRoot = Files.createDirectory(sandbox.resolve("public-private"));
        executor = new ManualExecutor();
        clock = new MutableClock();
        usage = new StorageDirectoryUsage(executor, clock);
        validations = new AtomicInteger();
        validation = () -> validations.incrementAndGet();
        CurrentUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        usage.close();
        CurrentUserHolder.clear();
    }

    @Test
    void sumsBothRootsNestedFilesAndThumbnailsButExcludesOnlyRootMetadata() throws Exception {
        Path nested = Files.createDirectories(publicRoot.resolve("2026/09/17"));
        Files.write(nested.resolve("image.jpg"), new byte[11]);
        Files.write(nested.resolve("image_t.jpg"), new byte[3]);
        Files.write(nested.resolve(MARKER), new byte[2]);
        Files.write(nested.resolve(".myhome-storage-probe-nested"), new byte[5]);
        Path privateNested = Files.createDirectories(privateRoot.resolve("1/2026"));
        Files.write(privateNested.resolve("document.bin"), new byte[7]);
        Files.write(privateNested.resolve("document_t.jpg"), new byte[4]);
        for (Path root : List.of(publicRoot, privateRoot)) {
            Files.write(root.resolve(MARKER), new byte[36]);
            Files.write(root.resolve(".myhome-storage-probe-old"), new byte[100]);
        }
        assertPending(request());
        assertEquals(0, validations.get());
        assertEquals(1, executor.queued());
        executor.runNext();
        assertEquals(32L, request().bytes());
        assertEquals(2, validations.get());
        assertNull(request().error());
        assertArrayEquals(new byte[11], Files.readAllBytes(nested.resolve("image.jpg")));
        assertEquals(36, Files.size(publicRoot.resolve(MARKER)));
    }

    @Test
    void emptyRootsCompleteWithZeroAndCompletionTimestamp() {
        assertPending(request());
        clock.advance(Duration.ofHours(1));
        executor.runNext();
        StorageDirectoryUsage.Snapshot result = request();
        assertEquals(0L, result.bytes());
        assertEquals(clock.instant().toString(), result.scannedAt());
        assertFalse(result.scanning());
        assertNull(result.error());
        assertEquals(0, executor.queued());
    }

    @Test
    void ttlStartsAtCompletionAndRefreshPreservesPreviousValueAndTimestamp() throws Exception {
        Files.write(publicRoot.resolve("one.bin"), new byte[5]);
        request();
        clock.advance(Duration.ofMinutes(5)); // Queue time must not consume the cache TTL.
        executor.runNext();
        StorageDirectoryUsage.Snapshot first = request();
        Files.write(privateRoot.resolve("two.bin"), new byte[7]);
        clock.advance(Duration.ofMillis(59_999));
        assertEquals(first, request());
        assertEquals(0, executor.queued());
        clock.advance(Duration.ofMillis(1));
        StorageDirectoryUsage.Snapshot refreshing = request();
        assertEquals(5L, refreshing.bytes());
        assertEquals(first.scannedAt(), refreshing.scannedAt());
        assertTrue(refreshing.scanning());
        assertEquals(1, executor.queued());
        clock.advance(Duration.ofMinutes(2));
        assertTrue(request().scanning());
        assertEquals(1, executor.queued());
        executor.runNext();
        assertEquals(12L, request().bytes());
        assertEquals(clock.instant().toString(), request().scannedAt());
        assertFalse(request().scanning());
    }

    @Test
    void concurrentRequestsCoalesceBeforeQueueRunsAndDuringScan() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var callers = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<StorageDirectoryUsage.Snapshot>> requests = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                requests.add(callers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return request();
                }));
            }
            start.countDown();
            for (var result : requests) {
                assertPending(result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            callers.shutdownNow();
        }
        assertEquals(1, executor.queued());
        assertEquals(0, validations.get());
        executor.runNext();
        clock.advance(Duration.ofSeconds(60));
        usage.snapshot(1, publicRoot, privateRoot, () -> {
            clock.advance(Duration.ofMinutes(2));
            assertTrue(request().scanning());
            assertEquals(0, executor.queued(), "An executing task is still in flight");
        });
        executor.runNext();
        assertEquals(0L, request().bytes());
        assertEquals(0, executor.queued());
    }

    @Test
    void distinctRegisteredIdsHaveIndependentSnapshotsAndTtl() throws Exception {
        Path other = Files.createDirectory(sandbox.resolve("other"));
        Path otherPrivate = Files.createDirectory(sandbox.resolve("other-private"));
        Files.write(publicRoot.resolve("one"), new byte[3]);
        Files.write(otherPrivate.resolve("two"), new byte[8]);
        request();
        usage.snapshot(2, other, otherPrivate, validation);
        assertEquals(2, executor.queued());
        executor.runNext();
        assertEquals(3L, request().bytes());
        assertPending(usage.snapshot(2, other, otherPrivate, validation));
        executor.runNext();
        assertEquals(8L, usage.snapshot(2, other, otherPrivate, validation).bytes());
        usage.invalidate(1);
        assertEquals(8L, usage.snapshot(2, other, otherPrivate, validation).bytes());
    }

    @Test
    void symbolicFileDirectoryDanglingAndCycleLinksAreSkipped() throws Exception {
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Path sentinel = Files.write(outside.resolve("sentinel"), new byte[100]);
        Files.write(publicRoot.resolve("regular"), new byte[3]);
        Files.createSymbolicLink(publicRoot.resolve("file-link"), sentinel);
        Files.createSymbolicLink(publicRoot.resolve("dir-link"), outside);
        Files.createSymbolicLink(publicRoot.resolve("dangling"), outside.resolve("missing"));
        Files.createSymbolicLink(privateRoot.resolve("cycle"), privateRoot);
        request();
        executor.runNext();
        assertEquals(3L, request().bytes());
        assertArrayEquals(new byte[100], Files.readAllBytes(sentinel));
    }

    @Test
    void hardLinksAreDeduplicatedAcrossBothRootsAndNestedDirectories() throws Exception {
        Path original = Files.write(publicRoot.resolve("original"), new byte[13]);
        Files.createLink(privateRoot.resolve("private-link"), original);
        Path nested = Files.createDirectory(publicRoot.resolve("nested"));
        Files.createLink(nested.resolve("public-link"), original);
        Object key = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        request();
        executor.runNext();
        assertEquals(key == null ? 39L : 13L, request().bytes());
    }

    @Test
    void providerWithoutFileKeyCountsEachEntryAndSupportsPlainDirectoryStreams() throws Exception {
        Path original = Files.write(publicRoot.resolve("original"), new byte[9]);
        Files.createLink(privateRoot.resolve("link"), original);
        request();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("newDirectoryStream")) {
                return plainStream((DirectoryStream<Path>) invocation.callRealMethod());
            }
            Object result = invocation.callRealMethod();
            if (result instanceof BasicFileAttributes attributes) {
                return attributes(attributes, null, attributes.size(), attributes.isOther());
            }
            return result;
        })) {
            executor.runNext();
        }
        assertEquals(18L, request().bytes());
    }

    @Test
    void fileKeysAreScopedToTheFileSystemNotJustTheInode() throws Exception {
        Path original = Files.write(publicRoot.resolve("original"), new byte[9]);
        Files.createLink(privateRoot.resolve("link"), original);
        FileStore anotherStore = mock(FileStore.class);
        request();
        // Simulated different store, not an actual mounted disk.
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("getFileStore")
                    && ((Path) invocation.getArgument(0)).startsWith(privateRoot)) {
                return anotherStore;
            }
            return invocation.callRealMethod();
        })) {
            executor.runNext();
        }
        assertEquals(18L, request().bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mount", "reparse", "realpath-alias"})
    void excludesForeignMountsAndReparseDirectoriesBeforeOpeningTheirStreams(String kind) throws Exception {
        Path target = Files.createDirectory(sandbox.resolve("outside"));
        Path excluded = kind.equals("realpath-alias")
                ? Files.createSymbolicLink(publicRoot.resolve("excluded"), target)
                : Files.createDirectory(publicRoot.resolve("excluded"));
        Files.write((kind.equals("realpath-alias") ? target : excluded).resolve("sentinel"), new byte[100]);
        BasicFileAttributes directoryAttributes = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        Files.write(privateRoot.resolve("included"), new byte[4]);
        FileStore otherStore = mock(FileStore.class);
        request();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("newDirectoryStream")) {
                assertNotEquals(excluded, invocation.getArgument(0), "Must check before opening a stream");
                return plainStream((DirectoryStream<Path>) invocation.callRealMethod());
            }
            if (kind.equals("mount") && method.equals("getFileStore") && excluded.equals(invocation.getArgument(0))) {
                return otherStore;
            }
            Object result = invocation.callRealMethod();
            if (result instanceof BasicFileAttributes attributes && excluded.equals(invocation.getArgument(0))) {
                if (kind.equals("reparse")) {
                    return attributes(attributes, attributes.fileKey(), attributes.size(), true);
                }
                if (kind.equals("realpath-alias")) {
                    // Model a junction that the provider does not flag as symbolicLink/isOther.
                    return directoryAttributes;
                }
            }
            return result;
        })) {
            executor.runNext();
        }
        assertEquals(4L, request().bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-stream", "private-stream", "file-metadata"})
    void readFailureClearsOldValueWithoutPublishingPartialSumAndRetriesAfterSixtySeconds(String fault) throws Exception {
        Files.write(publicRoot.resolve("one"), new byte[3]);
        Path privateFile = Files.write(privateRoot.resolve("two"), new byte[7]);
        request();
        executor.runNext();
        assertEquals(10L, request().bytes());
        clock.advance(Duration.ofSeconds(60));
        assertTrue(request().scanning());
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object path = invocation.getArguments().length == 0 ? null : invocation.getArgument(0);
            if ((method.equals("newDirectoryStream") && path.equals(fault.equals("public-stream") ? publicRoot : privateRoot)
                    && !fault.equals("file-metadata"))
                    || (fault.equals("file-metadata") && method.equals("getFileStore") && privateFile.equals(path))) {
                throw new IOException("sensitive-provider-error: " + sandbox);
            }
            return invocation.callRealMethod();
        })) {
            executor.runNext();
        }
        assertFailed(request());
        clock.advance(Duration.ofMillis(59_999));
        assertFailed(request());
        assertEquals(0, executor.queued());
        clock.advance(Duration.ofMillis(1));
        StorageDirectoryUsage.Snapshot retry = request();
        assertTrue(retry.scanning());
        assertNull(retry.bytes());
        assertNull(retry.scannedAt());
        executor.runNext();
        assertEquals(10L, request().bytes());
        assertNull(request().error());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void validationFailureBeforeOrAfterTraversalClearsOldValueAndIsSafelyThrottled(int failAt) throws Exception {
        Files.write(publicRoot.resolve("fixture"), new byte[6]);
        request();
        executor.runNext();
        assertEquals(6L, request().bytes());
        clock.advance(Duration.ofSeconds(60));
        AtomicInteger calls = new AtomicInteger();
        usage.snapshot(1, publicRoot, privateRoot, () -> {
            if (calls.incrementAndGet() == failAt) {
                throw new IllegalArgumentException("marker mismatch: " + sandbox);
            }
        });
        executor.runNext();
        assertEquals(failAt, calls.get());
        assertFailed(request());
        clock.advance(Duration.ofSeconds(59));
        assertFailed(request());
        assertEquals(0, executor.queued());
        clock.advance(Duration.ofSeconds(1));
        request();
        executor.runNext();
        assertEquals(6L, request().bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink-root", "symlink-ancestor", "noncanonical"})
    void scannerRejectsUnsafeRootEvenIfValidationCallbackDoesNothing(String form) throws Exception {
        Path supplied;
        if (form.equals("symlink-root")) {
            supplied = Files.createSymbolicLink(sandbox.resolve("alias"), publicRoot);
        } else if (form.equals("symlink-ancestor")) {
            Path parent = Files.createSymbolicLink(sandbox.resolve("alias"), sandbox);
            supplied = parent.resolve(publicRoot.getFileName());
        } else {
            supplied = Path.of(publicRoot + "/../" + publicRoot.getFileName());
        }
        usage.snapshot(1, supplied, privateRoot, validation);
        executor.runNext();
        assertFailed(usage.snapshot(1, supplied, privateRoot, validation));
    }

    @Test
    void overflowIsFailureRatherThanWrappedOrPartialBytes() throws Exception {
        Files.write(publicRoot.resolve("huge"), new byte[1]);
        Files.write(privateRoot.resolve("another"), new byte[1]);
        request();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("newDirectoryStream")) {
                return plainStream((DirectoryStream<Path>) invocation.callRealMethod());
            }
            Object result = invocation.callRealMethod();
            if (result instanceof BasicFileAttributes attributes && attributes.isRegularFile()) {
                return attributes(attributes, attributes.fileKey(), Long.MAX_VALUE, false);
            }
            return result;
        })) {
            executor.runNext();
        }
        assertFailed(request());
    }

    @Test
    void invalidationCancelsQueuedGenerationAndNeverQueuesTwoTasksForTheSameId() {
        request();
        usage.invalidate(1);
        assertPending(request());
        assertEquals(1, executor.queued());
        executor.runNext();
        assertEquals(0, validations.get(), "An invalidated queued generation must not even validate roots");
        assertPending(request());
        assertEquals(1, executor.queued());
        executor.runNext();
        assertEquals(0L, request().bytes());
    }

    @Test
    void invalidationDuringFinalValidationCannotPublishOldResultOrOverwriteNewGeneration() throws Exception {
        Files.write(publicRoot.resolve("file"), new byte[5]);
        AtomicInteger calls = new AtomicInteger();
        usage.snapshot(1, publicRoot, privateRoot, () -> {
            if (calls.incrementAndGet() == 2) {
                usage.invalidate(1);
                assertPending(request());
                assertEquals(0, executor.queued());
            }
        });
        executor.runNext();
        Files.write(publicRoot.resolve("file"), new byte[9]);
        assertPending(request());
        executor.runNext();
        assertEquals(9L, request().bytes());
    }

    @Test
    void invalidationOfCompletedResultClearsItImmediately() {
        request();
        executor.runNext();
        assertEquals(0L, request().bytes());
        usage.invalidate(1);
        assertPending(request());
        assertEquals(1, executor.queued());
    }

    @Test
    void immutableRegistrationCannotBeRedirectedByChangingPaths() throws Exception {
        request();
        executor.runNext();
        Path other = Files.createDirectory(sandbox.resolve("arbitrary"));
        assertFailed(usage.snapshot(1, other, privateRoot, validation));
        assertEquals(0, executor.queued());
        assertPending(request());
    }

    @Test
    void rejectedSubmissionIsAnIndependentThrottledUsageError() {
        executor.shutdown();
        assertFailed(request());
        assertFailed(request());
        assertEquals(0, validations.get());
        assertEquals(0, executor.queued());
    }

    @Test
    void closeCancelsQueuedTasksAndLateTasksCannotPublishOrRestart() throws Exception {
        request();
        Runnable queued = executor.takeNext();
        usage.close();
        queued.run();
        assertEquals(0, validations.get());
        assertEquals(new StorageDirectoryUsage.Snapshot(null, null, false, null), request());
        assertTrue(executor.isShutdown());
        assertEquals(1, executor.shutdownNowCalls);
        assertNotNull(StorageDirectoryUsage.class.getMethod("close").getAnnotation(PreDestroy.class));
    }

    @Test
    void closeDuringFinalValidationDiscardsCompletedBytes() {
        AtomicInteger calls = new AtomicInteger();
        usage.snapshot(1, publicRoot, privateRoot, () -> {
            if (calls.incrementAndGet() == 2) {
                usage.close();
            }
        });
        executor.runNext();
        assertNull(request().bytes());
        assertNull(request().scannedAt());
        assertFalse(request().scanning());
    }

    @Test
    void productionWorkerIsDaemonAndShutdownNowInterruptsIt() throws Exception {
        StorageDirectoryUsage production = new StorageDirectoryUsage();
        CompletableFuture<Thread> started = new CompletableFuture<>();
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        try {
            production.snapshot(1, publicRoot, privateRoot, () -> {
                started.complete(Thread.currentThread());
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                    throw new InterruptedIOException();
                }
            });
            Thread worker = started.get(5, TimeUnit.SECONDS);
            assertTrue(worker.isDaemon());
            assertEquals("storage-directory-usage", worker.getName());
            production.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            block.countDown();
            production.close();
        }
    }

    @Test
    void actualHttpReturnsFlatPendingSnapshotBeforeWorkerRunsThenCompletedBytes() throws Exception {
        String marker = "f4af5d50-bcf6-49b3-a34a-065d4a110b2a";
        Files.writeString(publicRoot.resolve(MARKER), marker);
        Files.writeString(privateRoot.resolve(MARKER), marker);
        Files.write(publicRoot.resolve("synthetic.bin"), new byte[17]);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getString("path")).thenReturn(publicRoot.toString());
        when(row.getString("marker")).thenReturn(marker);
        when(row.getBoolean("is_default")).thenReturn(true);
        when(row.getString("initialization_state")).thenReturn("READY");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        StorageDirectoryRegistry registry = new StorageDirectoryRegistry(jdbc,
                mock(PlatformTransactionManager.class), "unused", usage);
        var mvc = MockMvcBuilders.standaloneSetup(new StorageDirectoryController(registry)).build();
        CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(1L, "admin", CurrentUserHolder.ROLE_ADMIN,
                java.util.Set.of(com.familyhome.common.auth.PermissionCatalog.STORAGE_ACCESS)));
        mvc.perform(get("/api/b/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directories[0].directoryBytes").doesNotExist())
                .andExpect(jsonPath("$.data.directories[0].directoryScannedAt").doesNotExist())
                .andExpect(jsonPath("$.data.directories[0].directoryScanning").value(true))
                .andExpect(jsonPath("$.data.directories[0].directoryError").doesNotExist())
                .andExpect(jsonPath("$.data.directories[0].totalBytes").isNumber());
        assertEquals(1, executor.queued(), "HTTP has returned; the worker has not executed yet");
        CurrentUserHolder.clear();
        clearInvocations(jdbc);
        executor.runNext();
        verifyNoInteractions(jdbc);
        CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(1L, "admin", CurrentUserHolder.ROLE_ADMIN,
                java.util.Set.of(com.familyhome.common.auth.PermissionCatalog.STORAGE_ACCESS)));
        mvc.perform(get("/api/b/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directories[0].directoryBytes").value(17))
                .andExpect(jsonPath("$.data.directories[0].directoryScannedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.data.directories[0].directoryScanning").value(false));
        Files.delete(privateRoot.resolve(MARKER));
        mvc.perform(get("/api/b/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directories[0].available").value(false))
                .andExpect(jsonPath("$.data.directories[0].directoryBytes").doesNotExist())
                .andExpect(jsonPath("$.data.directories[0].directoryScanning").value(false));
    }

    private StorageDirectoryUsage.Snapshot request() {
        return usage.snapshot(1, publicRoot, privateRoot, validation);
    }

    private static void assertPending(StorageDirectoryUsage.Snapshot result) {
        assertNull(result.bytes());
        assertNull(result.scannedAt());
        assertTrue(result.scanning());
        assertNull(result.error());
    }

    private static void assertFailed(StorageDirectoryUsage.Snapshot result) {
        assertNull(result.bytes());
        assertNull(result.scannedAt());
        assertFalse(result.scanning());
        assertEquals(ERROR, result.error());
    }

    private static BasicFileAttributes attributes(BasicFileAttributes original, Object key, long size, boolean other) {
        BasicFileAttributes result = mock(BasicFileAttributes.class);
        when(result.isRegularFile()).thenReturn(original.isRegularFile());
        when(result.isDirectory()).thenReturn(original.isDirectory());
        when(result.isSymbolicLink()).thenReturn(original.isSymbolicLink());
        when(result.isOther()).thenReturn(other);
        when(result.size()).thenReturn(size);
        when(result.fileKey()).thenReturn(key);
        return result;
    }

    private static DirectoryStream<Path> plainStream(DirectoryStream<Path> delegate) {
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return delegate.iterator();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        private int shutdownNowCalls;

        synchronized int queued() {
            return queue.size();
        }

        synchronized Runnable takeNext() {
            return queue.removeFirst();
        }

        void runNext() {
            takeNext().run();
        }

        @Override
        public synchronized void execute(Runnable task) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            queue.addLast(task);
        }

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNowCalls++;
            List<Runnable> pending = List.copyOf(queue);
            queue.clear();
            return pending;
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
