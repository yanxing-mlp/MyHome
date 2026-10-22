package com.familyhome.boot;

import com.familyhome.boot.config.CurrentUserInterceptor;
import com.familyhome.boot.config.WebMvcConfig;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.DataScope;
import com.familyhome.file.biz.config.FileStorageConfig;
import com.familyhome.file.biz.dao.FileObjectMapper;
import com.familyhome.file.biz.entity.FileObjectDO;
import com.familyhome.file.biz.storage.FileStorageWriter;
import com.familyhome.file.biz.storage.LocalStorageClient;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Isolated storage regression: only invocation-owned real temporary paths, no Boot application,
 * database, registry initialization, external media, or network. Mapper/registry are mocks;
 * storage, routing and the Spring MVC resource-handler chain are real.
 */
class StorageRoutingTest {
    private static final String DATE = "2026/09/17";
    private static final byte[] MAIN = "synthetic-main-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMB = "synthetic-thumbnail".getBytes(StandardCharsets.UTF_8);
    private static final DataPartition PRIVATE = new DataPartition(DataScope.PRIVATE, 1L);

    @TempDir
    Path temporaryDirectory;

    private Path sandbox;
    private Path root1;
    private Path root2;
    private Path private1;
    private Path private2;
    private final AtomicLong defaultId = new AtomicLong(1L);
    private StorageDirectoryRegistry registry;
    private FileStorageConfig config;
    private LocalStorageClient storage;
    private FileObjectMapper mapper;
    private FileStorageWriter writer;
    private CurrentUserInterceptor interceptor;
    private AnnotationConfigWebApplicationContext webContext;

    @BeforeEach
    void setUp() throws IOException {
        CurrentUserHolder.clear();
        // macOS /var commonly points to /private/var; the production resolver rejects symlinks.
        sandbox = temporaryDirectory.toRealPath();
        root1 = Files.createDirectory(sandbox.resolve("root1"));
        root2 = Files.createDirectory(sandbox.resolve("root2"));
        private1 = Files.createDirectory(sandbox.resolve("root1-private"));
        private2 = Files.createDirectory(sandbox.resolve("root2-private"));
        defaultId.set(1L);
        registry = mock(StorageDirectoryRegistry.class);
        when(registry.legacyPath()).thenReturn(root1);
        when(registry.defaultIdForUpload()).thenAnswer(invocation -> defaultId.get());
        when(registry.pathFor(anyLong(), anyBoolean())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            boolean privateFile = invocation.getArgument(1);
            if (id != 1L && id != 2L) {
                throw new IllegalArgumentException("Unregistered test directory: " + id);
            }
            return rootFor(id, privateFile);
        });
        config = new FileStorageConfig();
        config.setRoot(root1.toString());
        config.setDirectoryRegistry(registry);
        // Managed LocalStorageClient deliberately does not create roots: fixtures did it above.
        storage = new LocalStorageClient(config);
        mapper = mock(FileObjectMapper.class);
        writer = new FileStorageWriter(storage, mapper, config);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
        if (webContext != null) {
            webContext.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "private-documents/1/", "private-videos/1/"})
    void legacyKeysStayInRootOneAndNewKeysStripOnlyTheirRoutingMarkers(String prefix) throws Exception {
        String oldKey = prefix + DATE + "/old.bin";
        String oldThumb = prefix + DATE + "/old_t.jpg";
        put(oldKey, MAIN);
        put(oldThumb, THUMB);
        assertStored(oldKey, expectedPath(1L, prefix, DATE + "/old.bin"), MAIN);
        assertStored(oldThumb, expectedPath(1L, prefix, DATE + "/old_t.jpg"), THUMB);
        Set<Path> before = filesIn(root1, private1);

        defaultId.set(2L);
        String directory = config.directoryForUpload(prefix + DATE);
        assertEquals(prefix + "_storage/2/" + DATE, directory);
        String newKey = directory + "/new.bin";
        put(newKey, MAIN);
        assertStored(newKey, expectedPath(2L, prefix, DATE + "/new.bin"), MAIN);
        assertEquals(before, filesIn(root1, private1), "Changing the default must not write into root1");
        assertStored(oldKey, expectedPath(1L, prefix, DATE + "/old.bin"), MAIN);
        assertStored(oldThumb, expectedPath(1L, prefix, DATE + "/old_t.jpg"), THUMB);
        assertEquals(root1.toString(), config.getRoot());
        assertEquals(private1, config.getPrivateRoot());

        // A changed environment setting is not allowed to reinterpret historical keys either.
        config.setRoot(root2.toString());
        defaultId.set(1L);
        assertEquals(prefix + DATE, config.directoryForUpload(prefix + DATE));
        assertStored(oldKey, expectedPath(1L, prefix, DATE + "/old.bin"), MAIN);
        assertStored(newKey, expectedPath(2L, prefix, DATE + "/new.bin"), MAIN);
        storage.delete(newKey);
        assertFalse(storage.exists(newKey));
        assertStored(oldKey, expectedPath(1L, prefix, DATE + "/old.bin"), MAIN);
    }

    @Test
    void publicByteWriterUsesSelectedRootWithoutMovingHistoricalBytes() throws Exception {
        FileStorageWriter.Written old = writer.write(MAIN, "png");
        assertTrue(old.getFileKey().matches("[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+\\.png"));
        assertStored(old.getFileKey(), root1.resolve(old.getFileKey()), MAIN);
        Set<Path> before = filesIn(root1, private1);

        defaultId.set(2L);
        FileStorageWriter.Written uploaded = writer.write(THUMB, "png");
        assertTrue(uploaded.getFileKey().startsWith("_storage/2/"));
        assertStored(uploaded.getFileKey(), root2.resolve(uploaded.getFileKey().substring("_storage/2/".length())), THUMB);
        assertEquals(FileStorageWriter.calculateMd5(THUMB), uploaded.getMd5());
        assertFalse(uploaded.isDuplicated());
        assertNull(uploaded.getThumbKey());
        assertEquals(before, filesIn(root1, private1));
        assertStored(old.getFileKey(), root1.resolve(old.getFileKey()), MAIN);
        verify(mapper).selectByMd5(FileStorageWriter.calculateMd5(THUMB));
        verify(mapper, never()).selectPrivateDocumentByMd5(anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L})
    void privateDocumentByteWriterKeepsOwnerBeforeNamespaceAndUsesSiblingRoot(long id) throws Exception {
        signIn();
        defaultId.set(id);
        FileStorageWriter.Written uploaded = writer.writeDocument(MAIN, "txt", PRIVATE);
        String keyPrefix = routedPrefix(id, "private-documents/1/");
        assertTrue(uploaded.getFileKey().startsWith(keyPrefix));
        String relative = uploaded.getFileKey().substring(keyPrefix.length());
        assertTrue(relative.matches("[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+\\.txt"));
        assertStored(uploaded.getFileKey(), rootFor(id, true).resolve("1").resolve(relative), MAIN);
        assertFalse(uploaded.isDuplicated());
        assertNull(uploaded.getThumbKey());
        assertEquals(FileStorageWriter.calculateMd5(MAIN), uploaded.getMd5());
        assertTrue(filesIn(root1, root2).isEmpty(), "Private bytes must never enter either public root");
        verify(mapper).selectPrivateDocumentByMd5(FileStorageWriter.calculateMd5(MAIN), 1L);
        verify(mapper, never()).selectByMd5(anyString());
    }

    @ParameterizedTest
    @CsvSource({"1,PUBLIC", "2,PUBLIC", "1,PRIVATE", "2,PRIVATE"})
    void videoStreamWriterUsesSelectedRootAndPreservesPrivatePrefixOrder(long id, DataScope scope) throws Exception {
        signIn();
        defaultId.set(id);
        DataPartition partition = new DataPartition(scope, scope == DataScope.PRIVATE ? 1L : 0L);
        FileStorageWriter.Written uploaded;
        try (var input = new ByteArrayInputStream(MAIN)) {
            uploaded = writer.writeVideoStream(input, "mp4", partition);
            assertEquals(0, input.available(), "The storage write must consume the supplied stream");
        }
        boolean privateFile = scope == DataScope.PRIVATE;
        String prefix = routedPrefix(id, privateFile ? "private-videos/1/" : "");
        assertTrue(uploaded.getFileKey().startsWith(prefix));
        String relative = uploaded.getFileKey().substring(prefix.length());
        assertTrue(relative.matches((privateFile ? "" : "videos/")
                + "[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+\\.mp4"));
        Path expected = rootFor(id, privateFile).resolve(privateFile ? "1/" + relative : relative);
        assertStored(uploaded.getFileKey(), expected, MAIN);
        assertEquals(FileStorageWriter.calculateMd5(MAIN), uploaded.getMd5());
        assertFalse(uploaded.isDuplicated());
        assertNull(uploaded.getThumbKey());
        assertEquals(Set.of(expected), filesIn(root1, root2, private1, private2));
        verifyNoInteractions(mapper);
    }

    @Test
    void privateWritersRejectForgedOwnerBeforeSelectingAnyRootOrWriting() throws Exception {
        signIn();
        DataPartition anotherOwner = new DataPartition(DataScope.PRIVATE, 2L);
        clearInvocations(registry);
        assertThrows(IllegalArgumentException.class, () -> writer.writeDocument(MAIN, "txt", anotherOwner));
        assertThrows(IllegalArgumentException.class,
                () -> writer.writeVideoStream(new ByteArrayInputStream(MAIN), "mp4", anotherOwner));
        verifyNoInteractions(mapper, registry);
        assertTrue(filesIn(root1, root2, private1, private2).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateMainAndThumbnailStayTogetherAcrossRepeatedDefaultSwitches(boolean privateDocument) throws Exception {
        signIn();
        String prefix = privateDocument ? "private-documents/1/" : "";
        String sourceKey = prefix + DATE + "/source.png";
        String sourceThumb = prefix + DATE + "/source_t.jpg";
        put(sourceKey, MAIN);
        put(sourceThumb, THUMB);
        Set<Path> originals = filesIn(root1, root2, private1, private2);
        FileObjectDO source = duplicateSource(sourceKey, sourceThumb, privateDocument);
        // Switch once during the database lookup, and again between main/thumbnail links.
        if (privateDocument) {
            when(mapper.selectPrivateDocumentByMd5(anyString(), eq(1L))).thenAnswer(invocation -> {
                switchDefault();
                return source;
            });
        } else {
            when(mapper.selectByMd5(anyString())).thenAnswer(invocation -> {
                switchDefault();
                return source;
            });
        }
        LocalStorageClient switchingStorage = spy(storage);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            switchDefault();
            return null;
        }).when(switchingStorage).link(anyString(), anyString());
        FileStorageWriter switchingWriter = new FileStorageWriter(switchingStorage, mapper, config);
        Set<String> issuedKeys = new LinkedHashSet<>();

        for (long selected : new long[] {2L, 1L, 2L}) {
            defaultId.set(selected);
            clearInvocations(registry, switchingStorage);
            FileStorageWriter.Written result = privateDocument
                    ? switchingWriter.writeDocument(MAIN, "png", PRIVATE) : switchingWriter.write(MAIN, "png");
            assertTrue(result.isDuplicated());
            assertNotNull(result.getThumbKey());
            assertNotEquals(sourceKey, result.getFileKey());
            assertNotEquals(sourceThumb, result.getThumbKey());
            assertTrue(issuedKeys.add(result.getFileKey()), "Every duplicate gets an independent key");
            String selectedPrefix = routedPrefix(selected, prefix);
            assertTrue(result.getFileKey().startsWith(selectedPrefix));
            assertTrue(result.getThumbKey().startsWith(selectedPrefix));
            String relative = result.getFileKey().substring(selectedPrefix.length());
            String thumbRelative = result.getThumbKey().substring(selectedPrefix.length());
            Path expected = expectedPath(selected, prefix, relative);
            Path expectedThumb = expectedPath(selected, prefix, thumbRelative);
            assertEquals(expected.getParent(), expectedThumb.getParent());
            assertStored(result.getFileKey(), expected, MAIN);
            assertStored(result.getThumbKey(), expectedThumb, THUMB);
            verify(registry, times(1)).defaultIdForUpload();
            verify(switchingStorage).link(sourceKey, result.getFileKey());
            verify(switchingStorage).link(sourceThumb, result.getThumbKey());
            if (selected == 2L) {
                assertEquals(originals, filesIn(root1, private1));
            }

            switchingWriter.discard(result.getFileKey(), result.getThumbKey());
            assertFalse(Files.exists(expected));
            assertFalse(Files.exists(expectedThumb));
            assertStored(sourceKey, expectedPath(1L, prefix, DATE + "/source.png"), MAIN);
            assertStored(sourceThumb, expectedPath(1L, prefix, DATE + "/source_t.jpg"), THUMB);
            assertEquals(originals, filesIn(root1, root2, private1, private2));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void linkFallsBackToIndependentCopyWhenCreateLinkThrows(boolean privateFile) throws Exception {
        String prefix = privateFile ? "private-documents/1/" : "";
        String sourceKey = prefix + DATE + "/source.bin";
        String destinationKey = prefix + "_storage/2/" + DATE + "/copy.bin";
        put(sourceKey, MAIN);
        Path source = expectedPath(1L, prefix, DATE + "/source.bin");
        Path destination = expectedPath(2L, prefix, DATE + "/copy.bin");
        // Fault injection only: two local test folders are NOT a real Windows/cross-volume test.
        // A custom answer avoids executing the real createLink while configuring the static mock.
        try (MockedStatic<Files> fileOperations = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("createLink")) {
                throw new FileSystemException(destination.toString(), source.toString(), "simulated link failure");
            }
            return invocation.callRealMethod();
        })) {
            storage.link(sourceKey, destinationKey);
            fileOperations.verify(() -> Files.createLink(destination, source), times(1));
        }
        assertStored(destinationKey, destination, MAIN);
        assertFalse(Files.isSameFile(source, destination), "Fallback must really copy, not share an inode");
        Files.write(destination, THUMB);
        assertArrayEquals(MAIN, Files.readAllBytes(source));
        storage.delete(destinationKey);
        assertFalse(Files.exists(destination));
        assertStored(sourceKey, source, MAIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "../outside.png", "2026/../outside.png", "_storage/2/../outside.png",
            "private-documents/1/_storage/2/../outside.png", "/absolute.png", "//server/share.png",
            "2026\\outside.png", "C:\\outside.png", "image.png:secret", "_storage/2/image.png:secret",
            ".myhome-storage-id", "2026/.hidden/image.png", "_storage/2/.myhome-storage-id",
            "private-videos/1/_storage/2/.myhome-storage-id", "2026/./image.png", "2026//image.png",
            "2026/image.png/", "2026/image.png.", "2026/image.png ", "2026/control\n.png",
            "_storage/999/image.png", "private-documents/1/_storage/999/image.png",
            "private-videos/1/_storage/999/image.png", "_storage/0/image.png", "_storage/-1/image.png",
            "_storage/no-id/image.png", "_storage/02/image.png", "_storage/2", "_storage/2/",
            "private-documents/0/image.png", "private-documents/not-owner/image.png", "private-videos/1"
    })
    void unsafeOrUnregisteredKeysAreRejectedByAllLocalOperations(String key) throws Exception {
        String safeKey = DATE + "/safe.bin";
        put(safeKey, MAIN);
        Set<Path> before = filesIn(root1, root2, private1, private2);
        assertRejectedByStorage(key, safeKey);
        assertEquals(before, filesIn(root1, root2, private1, private2));
        assertStored(safeKey, root1.resolve(safeKey), MAIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "private-documents/1/", "private-videos/1/"})
    void symlinkFilesAndAncestorsAreRejectedWithoutTouchingTheirTargets(String prefix) throws Exception {
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Path sentinel = Files.write(outside.resolve("sentinel.bin"), MAIN);
        String safeKey = DATE + "/safe.bin";
        put(safeKey, THUMB);
        for (long id : new long[] {1L, 2L}) {
            Path fileLink = expectedPath(id, prefix, DATE + "/linked.bin");
            Files.createDirectories(fileLink.getParent());
            Files.createSymbolicLink(fileLink, sentinel);
            String fileLinkKey = routedPrefix(id, prefix) + DATE + "/linked.bin";
            assertRejectedByStorage(fileLinkKey, safeKey);

            Path directoryLink = expectedPath(id, prefix, "linked-directory");
            Files.createDirectories(directoryLink.getParent());
            Files.createSymbolicLink(directoryLink, outside);
            assertRejectedByStorage(routedPrefix(id, prefix) + "linked-directory/sentinel.bin", safeKey);
            assertTrue(Files.isSymbolicLink(fileLink));
            assertTrue(Files.isSymbolicLink(directoryLink));
            assertArrayEquals(MAIN, Files.readAllBytes(sentinel));
        }
        assertStored(safeKey, root1.resolve(safeKey), THUMB);
    }

    @Test
    void actualMvcResourceChainServesLegacyAndNewImagesAndByteRangesAfterSwitch() throws Exception {
        byte[] oldImage = png(0x336699);
        byte[] newImage = png(0x993366);
        String oldKey = DATE + "/image.png";
        String oldThumb = DATE + "/image_t.png";
        put(oldKey, oldImage);
        put(oldThumb, oldImage);
        MockMvc mvc = startMvc();
        assertImageAndRange(mvc, oldKey, oldImage);

        defaultId.set(2L);
        String newKey = config.directoryForUpload(DATE) + "/image.png";
        String newThumb = config.directoryForUpload(DATE) + "/image_t.png";
        put(newKey, newImage);
        put(newThumb, newImage);
        // Same physical relative filename in both roots proves old keys are not reinterpreted.
        for (long ignored : new long[] {2L, 1L, 2L}) {
            defaultId.set(ignored);
            assertImageAndRange(mvc, oldKey, oldImage);
            assertImageAndRange(mvc, oldThumb, oldImage);
            assertImageAndRange(mvc, newKey, newImage);
            assertImageAndRange(mvc, newThumb, newImage);
        }
        verifyNoInteractions(interceptor, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-documents/1/", "private-videos/1/"})
    void actualMvcResourceChainRejectsBothPrivateNamespacesEvenWithMisplacedPublicCopies(String prefix) throws Exception {
        MockMvc mvc = startMvc();
        for (long id : new long[] {1L, 2L}) {
            defaultId.set(id);
            String key = config.directoryForUpload(prefix + DATE) + "/private.png";
            put(key, MAIN);
            assertStored(key, expectedPath(id, prefix, DATE + "/private.png"), MAIN);
            // Ensure 404 exercises the deny rule, not merely an absent file in public storage.
            writeFixture(root1.resolve(key), MAIN);
            writeFixture(root2.resolve(key), MAIN);
            for (String path : List.of(key, key.replace("private.png", "private_t.png"))) {
                writeFixture(root1.resolve(path), MAIN);
                writeFixture(root2.resolve(path), MAIN);
                assertResourceNotFound(mvc, path);
            }
        }
        verifyNoInteractions(interceptor, mapper);
    }

    @Test
    void actualMvcResourceChainRejectsHiddenMarkersUnknownRootsAndSymlinks() throws Exception {
        MockMvc mvc = startMvc();
        for (String marker : List.of(".myhome-storage-id", DATE + "/.hidden.png")) {
            writeFixture(root1.resolve(marker), MAIN);
            writeFixture(root2.resolve(marker), MAIN);
            assertResourceNotFound(mvc, marker);
            assertResourceNotFound(mvc, "_storage/2/" + marker);
        }
        String unknown = "_storage/999/" + DATE + "/image.png";
        writeFixture(root1.resolve(unknown), MAIN);
        assertResourceNotFound(mvc, unknown);
        Path outside = Files.createDirectory(sandbox.resolve("outside-http"));
        Files.write(outside.resolve("image.png"), MAIN);
        for (long id : new long[] {1L, 2L}) {
            Path root = rootFor(id, false);
            Files.createSymbolicLink(root.resolve("linked.png"), outside.resolve("image.png"));
            Files.createSymbolicLink(root.resolve("linked-directory"), outside);
            assertResourceNotFound(mvc, routedPrefix(id, "") + "linked.png");
            assertResourceNotFound(mvc, routedPrefix(id, "") + "linked-directory/image.png");
        }
    }

    private void assertRejectedByStorage(String key, String safeKey) {
        assertAll("Reject unsafe key: " + key,
                () -> assertThrows(IllegalArgumentException.class, () -> config.resolvePath(key)),
                () -> assertThrows(IllegalArgumentException.class, () -> storage.exists(key)),
                () -> assertThrows(IllegalArgumentException.class, () -> storage.put(key, new ByteArrayInputStream(THUMB))),
                () -> assertThrows(IllegalArgumentException.class, () -> storage.delete(key)),
                () -> assertThrows(IllegalArgumentException.class, () -> storage.link(safeKey, key)),
                () -> assertThrows(IllegalArgumentException.class, () -> storage.link(key, safeKey)));
    }

    private void assertStored(String key, Path expected, byte[] bytes) throws IOException {
        assertEquals(expected, config.resolvePath(key), "Assert physical routing independently of the resolver");
        assertTrue(storage.exists(key));
        assertArrayEquals(bytes, Files.readAllBytes(expected));
    }

    private Path expectedPath(long id, String prefix, String relative) {
        return rootFor(id, !prefix.isEmpty()).resolve(prefix.isEmpty() ? relative : "1/" + relative);
    }

    private Path rootFor(long id, boolean privateFile) {
        if (id == 1L) {
            return privateFile ? private1 : root1;
        }
        if (id == 2L) {
            return privateFile ? private2 : root2;
        }
        throw new IllegalArgumentException("Unknown fixture root");
    }

    private static String routedPrefix(long id, String privatePrefix) {
        return privatePrefix + (id == 1L ? "" : "_storage/" + id + "/");
    }

    private static void signIn() {
        CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(1L, "storage-test", CurrentUserHolder.ROLE_MEMBER, java.util.Set.of()));
    }

    private void switchDefault() {
        defaultId.updateAndGet(id -> id == 1L ? 2L : 1L);
    }

    private void put(String key, byte[] bytes) {
        storage.put(key, new ByteArrayInputStream(bytes));
    }

    private static void writeFixture(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static Set<Path> filesIn(Path... roots) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(result::add);
            }
        }
        return result;
    }

    private static FileObjectDO duplicateSource(String key, String thumb, boolean privateDocument) {
        FileObjectDO source = new FileObjectDO();
        source.setId(10L);
        source.setFileKey(key);
        source.setThumbKey(thumb);
        source.setMd5(FileStorageWriter.calculateMd5(MAIN));
        source.setScope(privateDocument ? DataScope.PRIVATE : DataScope.PUBLIC);
        source.setOwnerId(privateDocument ? 1L : 0L);
        source.setBizType(privateDocument ? "document" : "ALBUM_IMAGE");
        return source;
    }

    private MockMvc startMvc() {
        interceptor = mock(CurrentUserInterceptor.class);
        webContext = new AnnotationConfigWebApplicationContext();
        webContext.setServletContext(new MockServletContext());
        webContext.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("storageConfig", config);
            beanFactory.registerSingleton("currentUserInterceptor", interceptor);
        });
        // Explicit registration only: no component scan, auto-configuration, database or registry bean.
        webContext.register(MvcOnlyConfiguration.class, WebMvcConfig.class);
        webContext.refresh();
        return MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcOnlyConfiguration {
    }

    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, color);
            }
        }
        try (var output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private static void assertImageAndRange(MockMvc mvc, String key, byte[] expected) throws Exception {
        MvcResult full = mvc.perform(get("/files/" + key))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(expected))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andReturn();
        assertInstanceOf(ResourceHttpRequestHandler.class, full.getHandler());
        MvcResult range = mvc.perform(get("/files/" + key).header(HttpHeaders.RANGE, "bytes=1-6"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1-6/" + expected.length))
                .andExpect(content().bytes(Arrays.copyOfRange(expected, 1, 7)))
                .andReturn();
        assertInstanceOf(ResourceHttpRequestHandler.class, range.getHandler());
    }

    private static void assertResourceNotFound(MockMvc mvc, String key) throws Exception {
        MvcResult result = mvc.perform(get("/files/" + key))
                .andExpect(status().isNotFound()).andReturn();
        assertInstanceOf(ResourceHttpRequestHandler.class, result.getHandler());
        mvc.perform(get("/files/" + key).header(HttpHeaders.RANGE, "bytes=0-3"))
                .andExpect(status().isNotFound());
    }
}
