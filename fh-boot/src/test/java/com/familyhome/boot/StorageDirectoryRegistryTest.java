package com.familyhome.boot;

import com.familyhome.boot.handler.GlobalExceptionHandler;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.biz.controller.b.StorageDirectoryController;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry.DirectoryStatus;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry.FolderEntry;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry.FolderListing;
import com.familyhome.file.biz.storage.StorageDirectoryUsage;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Real registry and invocation-owned filesystem only: no Spring context, database or business media.
 * JDBC invokes the production RowMapper against mocked ResultSets; the real TransactionTemplate
 * executes callbacks through a mock manager. The fixture does NOT model MySQL locks, durability,
 * isolation, constraints or rollback; assertions about transactions concern invocation order only.
 */
class StorageDirectoryRegistryTest {
    private static final String MARKER = ".myhome-storage-id";

    @TempDir
    Path temporaryDirectory;

    private Path sandbox;
    private JdbcFixture db;
    private PlatformTransactionManager transactions;
    private StorageDirectoryRegistry registry;
    private StorageDirectoryUsage scanner;
    private CommitAction afterNextCommit;

    @BeforeEach
    void setUp() throws IOException {
        CurrentUserHolder.clear();
        scanner = mock(StorageDirectoryUsage.class);
        when(scanner.snapshot(anyLong(), any(Path.class), any(Path.class), any()))
                .thenReturn(new StorageDirectoryUsage.Snapshot(null, null, true, null));
        // On macOS /var is a symlink; all paths passed to the registry start at the real temp root.
        sandbox = temporaryDirectory.toRealPath();
        db = new JdbcFixture();
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            db.events.add("begin");
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> {
            db.events.add("commit");
            if (afterNextCommit != null) {
                CommitAction action = afterNextCommit;
                afterNextCommit = null;
                action.run();
            }
            return null;
        }).when(transactions).commit(any(TransactionStatus.class));
        doAnswer(invocation -> {
            db.events.add("rollback");
            return null;
        }).when(transactions).rollback(any(TransactionStatus.class));
        registry = registryWith(sandbox.resolve("configured-root").toString());
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @ParameterizedTest
    @CsvSource({"anonymous,list", "anonymous,add", "anonymous,default", "anonymous,browse", "anonymous,roots",
            "MEMBER,list", "MEMBER,add", "MEMBER,default", "MEMBER,browse", "MEMBER,roots",
            "admin,list", "admin,add", "admin,default", "admin,browse", "admin,roots",
            "GUEST,browse", "GUEST,roots", "UNKNOWN,browse", "UNKNOWN,roots"})
    void managementRejectsNonAdminsBeforeAnyJdbcTransactionOrFileOperation(String role, String operation)
            throws Exception {
        Row row = readyRow(1, "legacy", true);
        Path proposed = sandbox.resolve("unauthorized-new-root");
        Map<String, String> before = tree();
        if (!role.equals("anonymous")) {
            CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(7L, "test-user", role, java.util.Set.of()));
        }
        // This one boundary test forbids even read-only Files calls; all other filesystem tests
        // below exercise the actual provider. Inputs are otherwise valid and writable.
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            throw new AssertionError("Filesystem accessed before authorization: " + invocation.getMethod());
        }); MockedStatic<FileSystems> systems = mockStatic(FileSystems.class, invocation -> {
            throw new AssertionError("Root enumeration before authorization");
        })) {
            BizException error = assertThrows(BizException.class, () -> {
                switch (operation) {
                    case "list" -> registry.listForAdmin();
                    case "add" -> registry.addForAdmin(proposed.toString());
                    case "default" -> registry.setDefaultForAdmin(row.id);
                    case "browse" -> registry.browseForAdmin(proposed.toString());
                    case "roots" -> registry.browseForAdmin(null);
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals(role.equals("anonymous") ? ErrorCode.USER_NOT_LOGIN : ErrorCode.USER_FORBIDDEN,
                    error.getCode());
            files.verifyNoInteractions();
            systems.verifyNoInteractions();
        }
        verifyNoInteractions(db.jdbc, transactions, scanner);
        assertEquals(before, tree());
    }

    @Test
    void legacyRootComesFromDatabaseEvenWhenOfflineAndConfigurationChanges() throws Exception {
        Row legacy = readyRow(1, "original-root", true);
        Files.delete(marker(legacy.root()));
        Files.delete(legacy.root());
        Map<String, String> before = tree();
        for (String config : List.of(sandbox.resolve("replacement").toString(), "invalid\0configuration")) {
            StorageDirectoryRegistry restarted = registryWith(config);
            assertDoesNotThrow(restarted::initialize);
            assertEquals(legacy.root(), restarted.legacyPath());
        }
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
        verifyNoInteractions(transactions);
    }

    @Test
    void firstInitializationRegistersLegacyFilesWithoutMovingOrOverwritingThem() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("configured-root"));
        Path sentinel = Files.writeString(root.resolve("synthetic-existing.txt"), "existing fixture");
        registry.initialize();

        Row row = db.rows.get(1L);
        assertNotNull(row);
        assertTrue(row.ready);
        assertTrue(row.isDefault);
        assertEquals(root, registry.legacyPath());
        assertMarkers(row);
        assertEquals("existing fixture", Files.readString(sentinel));
        assertEquals(1L, registry.defaultIdForUpload());
        Map<String, String> before = tree();
        db.mutations.clear();
        registry.initialize();
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty(), "A subsequent initialize must not register again");
    }

    @ParameterizedTest
    @CsvSource({"false,missing-marker", "true,missing-marker", "false,missing-root", "true,missing-root",
            "false,foreign-marker", "true,foreign-marker"})
    void readyInitializationNeverRepairsMissingRootsOrRepairsOrOverwritesMarkers(boolean privateSide, String damage)
            throws Exception {
        Row row = readyRow(1, "ready", true);
        damage(row, privateSide, damage);
        Map<String, String> before = tree();

        assertDoesNotThrow(registry::initialize);
        assertDoesNotThrow(registry::initialize);

        assertTrue(row.ready);
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
        verifyNoInteractions(transactions);
        assertThrows(IllegalArgumentException.class, () -> registry.pathFor(row.id, privateSide));
    }

    @ParameterizedTest
    @CsvSource({"false,missing-marker", "true,missing-marker", "false,missing-root", "true,missing-root"})
    void historicalReadOnlyDependsOnSelectedSideEvenWhenOtherSideIsUnavailable(boolean selectedPrivate, String damage)
            throws Exception {
        Row row = readyRow(1, "readable", true);
        damage(row, !selectedPrivate, damage);
        Map<String, String> before = tree();

        assertEquals(side(row, selectedPrivate), registry.pathFor(row.id, selectedPrivate));
        assertThrows(IllegalArgumentException.class, () -> registry.pathFor(row.id, !selectedPrivate));
        assertBadRequest(() -> registry.defaultIdForUpload());
        admin();
        DirectoryStatus status = registry.listForAdmin().directories().getFirst();
        assertFalse(status.available());
        assertFalse(status.writable());
        assertNull(status.totalBytes());
        assertEquals(before, tree(), "Read checks and failed upload probes must leave no files behind");
        assertTrue(db.mutations.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,missing-marker", "true,missing-marker", "false,foreign-marker", "true,foreign-marker",
            "false,short-marker", "true,short-marker", "false,long-marker", "true,long-marker",
            "false,directory-marker", "true,directory-marker", "false,symlink-marker", "true,symlink-marker"})
    void selectedSideRejectsAbsentForeignMalformedOrSymlinkMarkersWithoutReplacingThem(boolean privateSide, String damage)
            throws Exception {
        Row row = readyRow(1, "invalid-marker", true);
        damage(row, privateSide, damage);
        Map<String, String> before = tree();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.pathFor(1, privateSide));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains(sandbox.toString()));
        assertEquals(side(row, !privateSide), registry.pathFor(1, !privateSide));
        assertBadRequest(() -> registry.defaultIdForUpload());
        admin();
        assertFalse(registry.listForAdmin().directories().getFirst().available());
        registry.initialize();
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"blank", "relative", "dot", "dotdot", "double-slash", "trailing-slash",
            "filesystem-root", "nul", "too-long"})
    void additionRejectsNonCanonicalOrInvalidInputBeforeDatabaseAccess(String form) throws Exception {
        admin();
        String input = invalidInput(form);
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.addForAdmin(input));
        verifyNoInteractions(db.jdbc, transactions);
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "dot", "dotdot", "nul"})
    void historicalReadsRejectNonCanonicalDatabasePaths(String form) throws Exception {
        Row row = readyRow(1, "path-validation", true);
        row.path = invalidInput(form);
        Map<String, String> before = tree();
        assertThrows(IllegalArgumentException.class, () -> registry.pathFor(1, false));
        assertThrows(IllegalArgumentException.class, () -> registry.pathFor(1, true));
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-root", "private-root", "ancestor"})
    void additionRejectsSymlinkRootsAndAncestorsWithoutWritingThroughThem(String location) throws Exception {
        readyRow(1, "legacy", true);
        admin();
        Path target = Files.createDirectory(sandbox.resolve("link-target"));
        Path candidate = sandbox.resolve("candidate");
        switch (location) {
            case "public-root" -> Files.createSymbolicLink(candidate, target);
            case "private-root" -> {
                Files.createDirectory(candidate);
                Files.createSymbolicLink(privateRoot(candidate), target);
            }
            case "ancestor" -> {
                Path ancestor = Files.createSymbolicLink(sandbox.resolve("linked-parent"), target);
                candidate = ancestor.resolve("candidate");
            }
            default -> throw new AssertionError(location);
        }
        String requested = candidate.toString();
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.addForAdmin(requested));
        assertTrue(db.mutations.isEmpty());
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @CsvSource({"false,root", "true,root", "false,ancestor", "true,ancestor"})
    void historicalReadRejectsSymlinkRootsAndAncestors(boolean privateSide, String location) throws Exception {
        Row row = readyRow(1, "link-read", true);
        if (location.equals("root")) {
            Path root = side(row, privateSide);
            Path parked = sandbox.resolve("parked");
            Files.move(root, parked);
            Files.createSymbolicLink(root, parked);
        } else {
            Path realParent = Files.createDirectory(sandbox.resolve("real-parent"));
            Files.move(row.root(), realParent.resolve("link-read"));
            Files.move(privateRoot(row.root()), realParent.resolve("link-read-private"));
            Path linkedParent = Files.createSymbolicLink(sandbox.resolve("alias-parent"), realParent);
            row.path = linkedParent.resolve("link-read").toString();
        }
        Map<String, String> before = tree();
        assertThrows(IllegalArgumentException.class, () -> registry.pathFor(1, privateSide));
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-public", "same-private", "under-public", "under-private", "ancestor",
            "private-equals-public", "private-contains-public"})
    void additionRejectsPublicPrivateRootOverlapsBeforeMutatingFiles(String overlap) throws Exception {
        Row row = readyRow(1, switch (overlap) {
            case "private-equals-public" -> "candidate-private";
            case "private-contains-public" -> "candidate-private/child";
            default -> "existing/legacy";
        }, true);
        Path proposed = switch (overlap) {
            case "same-public" -> row.root();
            case "same-private" -> privateRoot(row.root());
            case "under-public" -> row.root().resolve("child");
            case "under-private" -> privateRoot(row.root()).resolve("child");
            case "ancestor" -> row.root().getParent();
            case "private-equals-public", "private-contains-public" -> sandbox.resolve("candidate");
            default -> throw new AssertionError(overlap);
        };
        admin();
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.addForAdmin(proposed.toString()));
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,file", "true,file", "false,subdirectory", "true,subdirectory",
            "false,foreign-marker", "true,foreign-marker"})
    void additionRejectsNonEmptyPublicOrPrivateDirectoryWithoutTouchingExistingEntries(boolean privateSide, String entry)
            throws Exception {
        readyRow(1, "legacy", true);
        Path root = Files.createDirectory(sandbox.resolve("nonempty"));
        Files.createDirectory(privateRoot(root));
        Path occupied = privateSide ? privateRoot(root) : root;
        switch (entry) {
            case "file" -> Files.writeString(occupied.resolve("synthetic.txt"), "preserve me");
            case "subdirectory" -> Files.createDirectory(occupied.resolve("empty-child"));
            case "foreign-marker" -> Files.writeString(marker(occupied), UUID.randomUUID().toString());
            default -> throw new AssertionError(entry);
        }
        admin();
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.addForAdmin(root.toString()));
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
    }

    @Test
    void newRegistrationWritesMatchingMarkersAfterPendingCommitAndRejectsReadyDuplicate() throws Exception {
        readyRow(1, "legacy", true);
        admin();
        Path root = sandbox.resolve("new-root");
        afterNextCommit = () -> {
            Row pending = db.rows.get(2L);
            assertNotNull(pending);
            assertFalse(pending.ready);
            assertFalse(Files.exists(marker(root)));
            assertFalse(Files.exists(marker(privateRoot(root))));
        };

        long id = registry.addForAdmin(root.toString());

        assertEquals(2L, id);
        Row added = db.rows.get(id);
        assertTrue(added.ready);
        assertFalse(added.isDefault);
        assertEquals(root.toString(), added.path);
        assertEquals(added.marker, UUID.fromString(added.marker).toString());
        assertMarkers(added);
        assertOnlyMarker(root);
        assertOnlyMarker(privateRoot(root));
        assertEquals(root, registry.pathFor(id, false));
        assertEquals(privateRoot(root), registry.pathFor(id, true));
        assertEquals(1L, registry.defaultIdForUpload());
        assertTrue(db.events.indexOf("commit") < db.events.indexOf("ready:2"));
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definition.capture());
        assertTrue(definition.getAllValues().stream().allMatch(value ->
                value.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        Map<String, String> before = tree();
        db.mutations.clear();
        assertBadRequest(() -> registry.addForAdmin(root.toString()));
        assertTrue(db.mutations.isEmpty());
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @CsvSource({"false,missing-marker", "true,missing-marker", "false,missing-root", "true,missing-root",
            "false,foreign-marker", "true,foreign-marker", "false,pending"})
    void unavailableDefaultNeverFallsBackToHealthyRegisteredDirectory(boolean privateSide, String failure)
            throws Exception {
        Row selected = readyRow(1, "default", true);
        readyRow(2, "healthy-alternative", false);
        damage(selected, privateSide, failure);
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.defaultIdForUpload());
        assertTrue(selected.isDefault);
        assertFalse(db.rows.get(2L).isDefault);
        assertEquals(List.of(JdbcFixture.SELECT + " WHERE is_default = 1"), db.queries);
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void uploadRejectsMissingOrAmbiguousDefaultRatherThanChoosingOne(int defaultCount) throws Exception {
        readyRow(1, "one", defaultCount == 2);
        readyRow(2, "two", defaultCount == 2);
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.defaultIdForUpload());
        assertEquals(before, tree());
        assertTrue(db.mutations.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,missing-marker", "true,missing-marker", "false,missing-root", "true,missing-root",
            "false,foreign-marker", "true,foreign-marker", "false,pending", "false,unknown-id"})
    void rejectedDefaultSwitchDoesNotIssueEvenTheFirstUpdateOfOldDefault(boolean privateSide, String failure)
            throws Exception {
        Row old = readyRow(1, "old-default", true);
        Row target = readyRow(2, "target", false);
        if (!failure.equals("unknown-id")) {
            damage(target, privateSide, failure);
        }
        admin();
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.setDefaultForAdmin(failure.equals("unknown-id") ? 999 : 2));
        // No fixture rollback is implemented: both the SQL trace and row values must stay intact.
        assertTrue(db.mutations.isEmpty(), "Validation must precede clearing the existing default");
        assertTrue(old.isDefault);
        assertFalse(target.isDefault);
        assertEquals(before, tree());
        verify(transactions).rollback(any(TransactionStatus.class));
        verify(transactions, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void successfulDefaultSwitchUsesTargetWithoutChangingHistoricalRootOrLeavingProbeFiles() throws Exception {
        Row old = readyRow(1, "legacy", true);
        Row target = readyRow(2, "target", false);
        admin();
        Map<String, String> before = tree();
        registry.setDefaultForAdmin(2);
        assertEquals(List.of("UPDATE file_storage_directory SET is_default = 0 WHERE is_default = 1",
                "UPDATE file_storage_directory SET is_default = 1 WHERE id = ?"), db.mutations);
        assertFalse(old.isDefault);
        assertTrue(target.isDefault);
        assertEquals(2, registry.defaultIdForUpload());
        assertEquals(old.root(), registry.legacyPath());
        assertEquals(before, tree());
        db.mutations.clear();
        registry.setDefaultForAdmin(2);
        assertTrue(db.mutations.isEmpty(), "Selecting the current healthy default is idempotent");
    }

    @Test
    void adminCapacityUsesPublicFileStoreOnceWithoutEnumeratingOrSummingDirectoryContents() throws Exception {
        Row row = readyRow(1, "capacity", true);
        Path nested = Files.createDirectories(row.root().resolve("synthetic/deep"));
        Files.writeString(nested.resolve("small.txt"), "tiny fixture, not the volume usage");
        Files.writeString(privateRoot(row.root()).resolve("other.txt"), "private fixture");
        FileStore store = Files.getFileStore(row.root());
        long total = store.getTotalSpace();
        long usedBefore = total - store.getUnallocatedSpace();
        long usableBefore = store.getUsableSpace();
        admin();
        DirectoryStatus status;
        // Guard enumeration APIs, not metadata or FileStore: capacity still comes from the real OS.
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (List.of("walk", "find", "list", "newDirectoryStream").contains(invocation.getMethod().getName())) {
                throw new AssertionError("Capacity must not enumerate directory entries");
            }
            return invocation.callRealMethod();
        })) {
            StorageDirectoryRegistry.Snapshot snapshot = registry.listForAdmin();
            assertEquals(1L, snapshot.defaultDirectoryId());
            assertNotNull(snapshot.refreshedAt());
            assertEquals(1, snapshot.directories().size());
            status = snapshot.directories().getFirst();
        }
        assertTrue(status.available());
        assertTrue(status.writable());
        assertNull(status.error());
        assertEquals(row.path, status.path());
        assertEquals(privateRoot(row.root()).toString(), status.privatePath());
        assertEquals(store.name() + " (" + store.type() + ")", status.volume());
        assertEquals(total, status.totalBytes(), "Do not add public and private filesystem capacities");
        assertSpaceNearSamples(status.usedBytes(), usedBefore, total - store.getUnallocatedSpace());
        assertSpaceNearSamples(status.usableBytes(), usableBefore, store.getUsableSpace());
        assertNull(status.directoryBytes());
        assertNull(status.directoryScannedAt());
        assertTrue(status.directoryScanning());
        assertNull(status.directoryError());
        verify(scanner).snapshot(eq(row.id), eq(row.root()), eq(privateRoot(row.root())), any());
        assertTrue(db.mutations.isEmpty());
        verifyNoInteractions(transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-marker", "missing-root", "foreign-marker", "pending", "invalid-path"})
    void unavailableOrPendingRowsInvalidateUsageWithoutDisplayingCachedBytes(String damage) throws Exception {
        Row row = readyRow(1, "usage-offline", true);
        when(scanner.snapshot(anyLong(), any(), any(), any()))
                .thenReturn(new StorageDirectoryUsage.Snapshot(123L, "2026-09-17T00:00:00Z", false, null));
        admin();
        assertEquals(123L, registry.listForAdmin().directories().getFirst().directoryBytes());
        clearInvocations(scanner);
        if (damage.equals("invalid-path")) {
            row.path = "bad\0path";
        } else {
            damage(row, false, damage);
        }
        DirectoryStatus status = registry.listForAdmin().directories().getFirst();
        assertNull(status.directoryBytes());
        assertNull(status.directoryScannedAt());
        assertFalse(status.directoryScanning());
        verify(scanner).invalidate(1L);
        verifyNoMoreInteractions(scanner);
        if (damage.equals("pending")) {
            assertTrue(status.available());
            assertFalse(status.writable());
            assertNotNull(status.totalBytes());
            assertEquals("目录登记尚未完成", status.error());
        }
    }

    @Test
    void usageFailureDoesNotChangeCapacityAvailabilityOrDefaultSelection() throws Exception {
        readyRow(1, "usage-legacy", true);
        Row target = readyRow(2, "usage-target", false);
        when(scanner.snapshot(anyLong(), any(), any(), any()))
                .thenReturn(new StorageDirectoryUsage.Snapshot(null, null, false, "目录大小统计失败，请稍后重试"));
        admin();
        DirectoryStatus status = registry.listForAdmin().directories().get(1);
        assertTrue(status.available());
        assertTrue(status.writable());
        assertNull(status.error());
        assertNotNull(status.totalBytes());
        assertNull(status.directoryBytes());
        assertNotNull(status.directoryError());
        clearInvocations(scanner);
        registry.setDefaultForAdmin(target.id);
        assertEquals(target.id, registry.defaultIdForUpload());
        verifyNoInteractions(scanner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerValidationRechecksBothRegisteredMarkersWithoutCurrentUserOrJdbc(boolean privateSide) throws Exception {
        Row row = readyRow(1, "worker-validation", true);
        admin();
        registry.listForAdmin();
        ArgumentCaptor<StorageDirectoryUsage.RootValidation> validation =
                ArgumentCaptor.forClass(StorageDirectoryUsage.RootValidation.class);
        verify(scanner).snapshot(eq(1L), eq(row.root()), eq(privateRoot(row.root())), validation.capture());
        CurrentUserHolder.clear();
        clearInvocations(db.jdbc, transactions);
        validation.getValue().validate();
        damage(row, privateSide, "foreign-marker");
        assertThrows(RuntimeException.class, () -> validation.getValue().validate());
        verifyNoInteractions(db.jdbc, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"initialize", "post", "replacement"})
    void interruptedPendingRegistrationRecoversOriginalDirectoryButRejectsReplacement(String recovery)
            throws Exception {
        readyRow(1, "legacy", true);
        admin();
        Path root = sandbox.resolve("interrupted");
        Path parkedPrivate = sandbox.resolve("temporarily-offline-private");
        afterNextCommit = () -> {
            assertFalse(db.rows.get(2L).ready);
            assertFalse(Files.exists(marker(root)));
            assertFalse(Files.exists(marker(privateRoot(root))));
            // Fault injection AFTER the mock pending commit, before the real marker phase.
            // Moving the original inode back later is not a real mount/crash/transaction test.
            Files.move(privateRoot(root), parkedPrivate);
        };
        assertBadRequest(() -> registry.addForAdmin(root.toString()));
        Row pending = db.rows.get(2L);
        assertNotNull(pending);
        assertFalse(pending.ready);
        assertEquals(pending.marker, Files.readString(marker(root)));
        assertFalse(Files.exists(privateRoot(root)));
        assertOnlyMarker(root);
        assumeTrue(pending.privateIdentity != null, "Missing-marker recovery requires provider fileKey support");
        Object publicMarkerKey = Files.readAttributes(marker(root), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        if (recovery.equals("replacement")) {
            // Keep the old directory alive so a newly created one cannot reuse its inode/fileKey.
            Files.createDirectory(privateRoot(root));
            Map<String, String> before = tree();
            db.mutations.clear();
            registry.initialize();
            assertBadRequest(() -> registry.addForAdmin(root.toString()));
            assertFalse(pending.ready);
            assertTrue(db.mutations.isEmpty());
            assertEquals(before, tree());
            assertFalse(Files.exists(marker(privateRoot(root))));
            return;
        }
        Files.move(parkedPrivate, privateRoot(root));
        db.mutations.clear();
        if (recovery.equals("post")) {
            assertEquals(2L, registry.addForAdmin(root.toString()));
        } else {
            registryWith("invalid\0configuration").initialize();
        }
        assertTrue(pending.ready);
        assertEquals(2, db.rows.size());
        assertEquals(List.of("UPDATE file_storage_directory SET initialization_state = 'READY' WHERE id = ?"),
                db.mutations);
        assertMarkers(pending);
        assertEquals(publicMarkerKey, Files.readAttributes(marker(root), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey(), "Recovery must preserve the already-installed marker");
        assertOnlyMarker(root);
        assertOnlyMarker(privateRoot(root));
        assertEquals(root, registry.pathFor(2, false));
        assertEquals(privateRoot(root), registry.pathFor(2, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-identity", "wrong-identity", "missing-root", "foreign-marker",
            "symlink-marker", "nonempty"})
    void pendingRecoveryRefusesUnprovenOrUnsafeDirectoriesAndDoesNotBlockOtherRows(String failure) throws Exception {
        Row pending = readyRow(1, "pending", true);
        pending.ready = false;
        if (failure.equals("missing-identity") || failure.equals("wrong-identity")) {
            Files.delete(marker(pending.root()));
            pending.publicIdentity = failure.equals("wrong-identity") ? "not-the-original-identity" : null;
        } else if (!failure.equals("nonempty")) {
            damage(pending, false, failure);
        }
        // id != 1 disallows content even if an existing marker matches; id1 intentionally keeps legacy data.
        if (failure.equals("nonempty")) {
            pending = readyRow(2, "pending-new", false);
            pending.ready = false;
            Files.writeString(pending.root().resolve("unexpected.txt"), "do not associate existing data");
        }
        Row recoverable = readyRow(3, "another-pending", false);
        recoverable.ready = false;
        Map<String, String> before = tree();
        registry.initialize();
        assertFalse(pending.ready);
        assertTrue(recoverable.ready, "An unsafe row must not prevent another row from recovering");
        assertEquals(before, tree(), "Recovery must never recreate a root or replace an existing marker");
        assertFalse(db.events.contains("ready:" + pending.id));
        assertTrue(db.events.contains("ready:3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "blank", "relative", "dot", "dotdot", "double-slash", "trailing-slash", "nul", "too-long"})
    void browsingRejectsExplicitEmptyOrNonCanonicalParametersBeforeJdbcAndFiles(String form) {
        admin();
        String input = invalidInput(form);
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            throw new AssertionError("Invalid input must not access the filesystem");
        })) {
            BizException error = assertThrows(BizException.class, () -> registry.browseForAdmin(input));
            assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(sandbox.toString()));
            files.verifyNoInteractions();
        }
        verifyNoInteractions(db.jdbc, transactions, scanner);
    }

    @Test
    void rootListingAndRootNavigationNeverEnumerateTheHostOutsideTheSandbox() throws Exception {
        admin();
        Path root = sandbox.getRoot();
        DirectoryStream<Path> emptyRoot = mock(DirectoryStream.class);
        when(emptyRoot.iterator()).thenReturn(List.<Path>of().iterator());
        // Root metadata may be checked, but its real directory stream must NEVER be opened.
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("newDirectoryStream") && root.equals(invocation.getArgument(0))) {
                return emptyRoot;
            }
            return browseFileCall(invocation, Set.of());
        })) {
            FolderListing roots = registry.browseForAdmin(null);
            assertNull(roots.path());
            assertNull(roots.parentPath());
            assertFalse(roots.selectable());
            assertNull(roots.disabledReason());
            assertTrue(roots.directories().contains(new FolderEntry(root.toString(), root.toString(), true, null)));
            files.verify(() -> Files.newDirectoryStream(any(Path.class)), never());

            FolderListing opened = registry.browseForAdmin(root.toString());
            assertEquals(root.toString(), opened.path());
            assertNull(opened.parentPath());
            assertFalse(opened.selectable());
            assertEquals("不能使用文件系统根目录", opened.disabledReason());
            assertTrue(opened.directories().isEmpty());
            assertBadRequest(() -> registry.addForAdmin(root.toString()));
        }
        verify(emptyRoot).close();
        assertBrowseOnlyReads();
    }

    @Test
    void browsingShowsOnlyImmediateFoldersAndChecksChildrenForNavigationNotRegistration() throws Exception {
        admin();
        Path parent = Files.createDirectory(sandbox.resolve("服务器 disk"));
        Path empty = Files.createDirectory(parent.resolve("a-empty"));
        Path occupied = Files.createDirectory(parent.resolve("b-occupied"));
        Files.createDirectory(occupied.resolve("grandchild-not-returned"));
        Files.writeString(parent.resolve("not-a-folder.txt"), "fixture only");
        Files.createSymbolicLink(parent.resolve("linked-folder"), empty);
        Files.createSymbolicLink(parent.resolve("dangling-link"), parent.resolve("absent"));
        Map<String, String> before = tree();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(parent, empty))) {
            FolderListing listing = registry.browseForAdmin(parent.toString());
            assertEquals(parent.toString(), listing.path());
            assertEquals(sandbox.toString(), listing.parentPath());
            assertFalse(listing.selectable());
            assertNotNull(listing.disabledReason());
            assertEquals(List.of(new FolderEntry("a-empty", empty.toString(), true, null),
                    new FolderEntry("b-occupied", occupied.toString(), true, null)), listing.directories());
            assertTrue(registry.browseForAdmin(empty.toString()).selectable());
            files.verify(() -> Files.newDirectoryStream(occupied), never());
        }
        assertFalse(Files.exists(privateRoot(empty)));
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyDirectoryIsSelectableWithAbsentOrEmptyPrivateSideWithoutCreatingAnything(boolean existingPrivate)
            throws Exception {
        admin();
        Path root = Files.createDirectory(sandbox.resolve("new-folder"));
        if (existingPrivate) {
            Files.createDirectory(privateRoot(root));
        }
        Map<String, String> before = tree();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root, privateRoot(root)))) {
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertTrue(listing.selectable());
            assertNull(listing.disabledReason());
            assertTrue(listing.directories().isEmpty());
            if (!existingPrivate) {
                files.verify(() -> Files.newDirectoryStream(privateRoot(root)), never());
            }
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @CsvSource({"false,false,root", "false,false,child", "false,false,grandchild", "false,false,missing",
            "true,false,root", "true,false,child", "true,false,grandchild", "true,false,missing",
            "false,true,root", "false,true,child", "false,true,grandchild", "false,true,missing",
            "true,true,root", "true,true,child", "true,true,grandchild", "true,true,missing"})
    void registeredPublicAndPrivateTreesIncludingPendingAreDeniedBeforeFilesystemAccess(
            boolean privateSide, boolean pending, String depth) throws Exception {
        Row row = readyRow(1, "registered", true);
        row.ready = !pending;
        Path selected = side(row, privateSide);
        Files.createDirectories(selected.resolve("child/grandchild"));
        selected = switch (depth) {
            case "root" -> selected;
            case "child" -> selected.resolve("child");
            case "grandchild" -> selected.resolve("child/grandchild");
            case "missing" -> selected.resolve("nonexistent");
            default -> throw new AssertionError(depth);
        };
        String requested = selected.toString();
        admin();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            throw new AssertionError("Registered tree must not be probed or enumerated");
        })) {
            BizException error = assertThrows(BizException.class, () -> registry.browseForAdmin(requested));
            assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
            assertEquals("无法浏览目录：已登记的公共或私人存储目录不能浏览", error.getMessage());
            assertNull(error.getCause());
            files.verifyNoInteractions();
        }
        assertEquals(List.of(JdbcFixture.SELECT + " ORDER BY id"), db.queries);
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ancestorsRemainNavigableButUnselectableAndRegisteredNamesAreDisabled(boolean pending) throws Exception {
        Row row = readyRow(1, "parent/registered", true);
        row.ready = !pending;
        Files.createDirectory(privateRoot(row.root()).resolve("must-not-be-disclosed"));
        admin();
        Path parent = row.root().getParent();
        Map<String, String> before = tree();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(parent, sandbox))) {
            FolderListing listing = registry.browseForAdmin(parent.toString());
            assertFalse(listing.selectable());
            assertTrue(listing.disabledReason().contains("重叠"));
            assertEquals(List.of("registered", "registered-private"),
                    listing.directories().stream().map(FolderEntry::name).toList());
            assertTrue(listing.directories().stream().allMatch(entry -> !entry.enterable()
                    && entry.disabledReason().equals("已登记的公共或私人存储目录不能浏览")));
            FolderListing grandparent = registry.browseForAdmin(sandbox.toString());
            assertFalse(grandparent.selectable());
            assertTrue(grandparent.directories().stream().anyMatch(entry -> entry.name().equals("parent") && entry.enterable()));
            files.verify(() -> Files.newDirectoryStream(row.root()), never());
            files.verify(() -> Files.newDirectoryStream(privateRoot(row.root())), never());
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-equals-public", "private-contains-public"})
    void previewRejectsOverlapOnProposedPrivateSideWithoutOpeningRegisteredStreams(String overlap) throws Exception {
        Path candidate = Files.createDirectory(sandbox.resolve("candidate"));
        readyRow(1, overlap.equals("private-equals-public") ? "candidate-private" : "candidate-private/registered", true);
        admin();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(candidate))) {
            FolderListing listing = registry.browseForAdmin(candidate.toString());
            assertFalse(listing.selectable());
            assertTrue(listing.disabledReason().contains("重叠"));
            assertTrue(listing.directories().isEmpty());
        }
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @CsvSource({"false,file", "true,file", "false,subdirectory", "true,subdirectory", "false,marker", "true,marker"})
    void previewDisablesNonemptyPublicOrPrivateSideWithoutReadingContents(boolean privateSide, String entry)
            throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("nonempty-preview"));
        Files.createDirectory(privateRoot(root));
        Path occupied = privateSide ? privateRoot(root) : root;
        if (entry.equals("subdirectory")) {
            Files.createDirectory(occupied.resolve("child"));
        } else {
            Files.writeString(occupied.resolve(entry.equals("marker") ? MARKER : "file.txt"), "synthetic fixture");
        }
        admin();
        Map<String, String> before = tree();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root, privateRoot(root)))) {
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertFalse(listing.selectable());
            assertEquals("新存储目录及其私人目录必须为空，不能关联已有资料目录", listing.disabledReason());
            assertEquals(!privateSide && entry.equals("subdirectory") ? 1 : 0, listing.directories().size());
            assertTrue(listing.directories().stream().allMatch(FolderEntry::enterable));
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @CsvSource({"false,read-only", "true,read-only", "false,full", "true,full",
            "false,unwritable", "true,unwritable", "true,unreadable", "true,unsearchable"})
    void previewDisablesBothSidesForDiskAndPermissionFailures(boolean privateSide, String failure) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("preview-permissions"));
        Files.createDirectory(privateRoot(root));
        Path affected = privateSide ? privateRoot(root) : root;
        FileStore store = mock(FileStore.class);
        when(store.isReadOnly()).thenReturn(failure.equals("read-only"));
        when(store.getUsableSpace()).thenReturn(failure.equals("full") ? 0L : 1024L);
        admin();
        Map<String, String> before = tree();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root, privateRoot(root)))) {
            files.when(() -> Files.getFileStore(affected)).thenReturn(store);
            if (failure.equals("unwritable")) {
                files.when(() -> Files.isWritable(affected)).thenReturn(false);
            } else if (failure.equals("unreadable")) {
                files.when(() -> Files.isReadable(affected)).thenReturn(false);
            } else if (failure.equals("unsearchable")) {
                assumeTrue(java.io.File.separatorChar != '\\');
                files.when(() -> Files.isExecutable(affected)).thenReturn(false);
            }
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertFalse(listing.selectable());
            assertNotNull(listing.disabledReason());
            assertTrue(listing.directories().isEmpty());
            assertFalse(listing.disabledReason().contains(sandbox.toString()));
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unwritable", "unreadable", "unsearchable", "read-only", "full"})
    void absentPrivateSideRequiresUsableParentWithoutCreatingIt(String failure) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("private-parent-check"));
        FileStore store = mock(FileStore.class);
        when(store.isReadOnly()).thenReturn(failure.equals("read-only"));
        when(store.getUsableSpace()).thenReturn(failure.equals("full") ? 0L : 1024L);
        admin();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root))) {
            files.when(() -> Files.getFileStore(sandbox)).thenReturn(store);
            switch (failure) {
                case "unwritable" -> files.when(() -> Files.isWritable(sandbox)).thenReturn(false);
                case "unreadable" -> files.when(() -> Files.isReadable(sandbox)).thenReturn(false);
                case "unsearchable" -> {
                    assumeTrue(java.io.File.separatorChar != '\\');
                    files.when(() -> Files.isExecutable(sandbox)).thenReturn(false);
                }
                default -> { }
            }
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertFalse(listing.selectable());
            assertNotNull(listing.disabledReason());
        }
        assertFalse(Files.exists(privateRoot(root)));
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "missing-private", "existing-private"})
    void previewUsesPostMountContainerRulesWithoutAccessingAnyRealMountPoint(String affectedSide) throws Exception {
        assumeTrue(java.io.File.separatorChar != '\\');
        Path root = Files.createDirectory(sandbox.resolve("mount-preview"));
        if (affectedSide.equals("existing-private")) {
            Files.createDirectory(privateRoot(root));
        }
        FileStore mountedStore = mock(FileStore.class);
        when(mountedStore.getUsableSpace()).thenReturn(1024L);
        admin();
        Map<String, String> before = tree();
        // Map only the mount-container constant to our TempDir; no /mnt directory is touched.
        try (MockedStatic<Path> paths = mockStatic(Path.class, CALLS_REAL_METHODS);
             MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root, privateRoot(root)))) {
            paths.when(() -> Path.of("/mnt")).thenReturn(sandbox);
            if (!affectedSide.equals("public")) {
                files.when(() -> Files.getFileStore(root)).thenReturn(mountedStore);
            }
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertFalse(listing.selectable());
            assertEquals("目标磁盘尚未挂载，不能在挂载容器中创建目录", listing.disabledReason());
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-link", "ancestor-link", "private-link", "private-file", "missing", "file", "reparse"})
    void browserRejectsAliasesMissingFoldersAndNonDirectoryPaths(String failure) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("alias-preview"));
        Path target = Files.createDirectory(sandbox.resolve("link-target-preview"));
        Path requested = root;
        switch (failure) {
            case "public-link" -> requested = Files.createSymbolicLink(sandbox.resolve("alias"), target);
            case "ancestor-link" -> {
                Files.createDirectory(target.resolve("child"));
                requested = Files.createSymbolicLink(sandbox.resolve("alias"), target).resolve("child");
            }
            case "private-link" -> Files.createSymbolicLink(privateRoot(root), target);
            case "private-file" -> Files.writeString(privateRoot(root), "not a folder");
            case "missing" -> requested = sandbox.resolve("missing");
            case "file" -> requested = Files.writeString(sandbox.resolve("not-directory"), "fixture");
            default -> { }
        }
        String input = requested.toString();
        admin();
        Map<String, String> before = tree();
        var reparse = mock(java.nio.file.attribute.BasicFileAttributes.class);
        when(reparse.isDirectory()).thenReturn(true);
        when(reparse.isOther()).thenReturn(true);
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root))) {
            if (failure.equals("reparse")) {
                files.when(() -> Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS)).thenReturn(reparse);
            }
            if (failure.startsWith("private-")) {
                FolderListing listing = registry.browseForAdmin(input);
                assertFalse(listing.selectable());
                assertNotNull(listing.disabledReason());
            } else {
                assertBadRequest(() -> registry.browseForAdmin(input));
                files.verify(() -> Files.newDirectoryStream(any(Path.class)), never());
            }
        }
        assertEquals(before, tree());
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "stream", "iterator", "provider", "security", "store", "private-stream"})
    void browserErrorsAndDisabledReasonsNeverExposeSqlProviderOrIoDetails(String failure) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("failure-preview"));
        Files.createDirectory(privateRoot(root));
        String secret = "sensitive-provider SQL SELECT credentials " + sandbox;
        if (failure.equals("jdbc")) {
            doThrow(new DataAccessResourceFailureException(secret)).when(db.jdbc)
                    .query(eq(JdbcFixture.SELECT + " ORDER BY id"), any(RowMapper.class));
        }
        DirectoryStream<Path> brokenStream = mock(DirectoryStream.class);
        when(brokenStream.iterator()).thenThrow(new DirectoryIteratorException(new IOException(secret)));
        admin();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (!method.equals("newDirectoryStream") && !method.equals("getFileStore")) {
                return browseFileCall(invocation, Set.of(root, privateRoot(root)));
            }
            Path path = invocation.getArgument(0);
            if (method.equals("newDirectoryStream") && path.equals(root)) {
                switch (failure) {
                    case "stream" -> throw new IOException(secret);
                    case "provider" -> throw new UnsupportedOperationException(secret);
                    case "security" -> throw new SecurityException(secret);
                    case "iterator" -> { return brokenStream; }
                    default -> { }
                }
            }
            if ((failure.equals("store") && method.equals("getFileStore") && path.equals(root))
                    || (failure.equals("private-stream") && method.equals("newDirectoryStream") && path.equals(privateRoot(root)))) {
                throw new IOException(secret);
            }
            return browseFileCall(invocation, Set.of(root, privateRoot(root)));
        })) {
            String message;
            if (failure.equals("store") || failure.equals("private-stream")) {
                FolderListing listing = registry.browseForAdmin(root.toString());
                assertFalse(listing.selectable());
                assertTrue(listing.directories().isEmpty());
                message = listing.disabledReason();
            } else {
                BizException error = assertThrows(BizException.class, () -> registry.browseForAdmin(root.toString()));
                assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
                assertNull(error.getCause());
                message = error.getMessage();
            }
            assertNotNull(message);
            assertFalse(message.contains(secret));
            assertFalse(message.contains("SELECT"));
            assertFalse(message.contains(sandbox.toString()));
            if (failure.equals("jdbc")) {
                files.verifyNoInteractions();
            }
        }
        if (failure.equals("iterator")) {
            verify(brokenStream).close();
        }
        assertBrowseOnlyReads();
    }

    @Test
    void unreadableChildIsDisabledWithoutOpeningItsStreamAndSiblingRemainsEnterable() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("child-permissions"));
        Path denied = Files.createDirectory(root.resolve("denied"));
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        admin();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root))) {
            files.when(() -> Files.isReadable(denied)).thenReturn(false);
            FolderListing listing = registry.browseForAdmin(root.toString());
            assertEquals(new FolderEntry("allowed", allowed.toString(), true, null), listing.directories().getFirst());
            assertFalse(listing.directories().get(1).enterable());
            assertEquals("目录不可读，请检查系统权限", listing.directories().get(1).disabledReason());
            assertFalse(listing.selectable());
        }
        assertBrowseOnlyReads();
    }

    @Test
    void invalidRegisteredMetadataFailsClosedInsteadOfExposingProtectedDirectories() throws Exception {
        Row row = readyRow(1, "metadata-preview", true);
        row.path = "invalid\0database-path";
        admin();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            throw new AssertionError("Bad metadata must fail before filesystem access");
        })) {
            assertBadRequest(() -> registry.browseForAdmin(sandbox.toString()));
            files.verifyNoInteractions();
        }
        assertBrowseOnlyReads();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postStillRejectsFilesAddedAfterSelectionOnEitherSide(boolean privateSide) throws Exception {
        readyRow(1, "post-recheck-legacy", true);
        Path root = Files.createDirectory(sandbox.resolve("post-recheck"));
        Files.createDirectory(privateRoot(root));
        admin();
        try (MockedStatic<Files> files = readOnlyBrowseFiles(Set.of(root, privateRoot(root)))) {
            assertTrue(registry.browseForAdmin(root.toString()).selectable());
        }
        assertBrowseOnlyReads();
        Files.writeString((privateSide ? privateRoot(root) : root).resolve("arrived-after-selection"), "keep me");
        Map<String, String> before = tree();
        assertBadRequest(() -> registry.addForAdmin(root.toString()));
        assertTrue(db.mutations.isEmpty());
        assertEquals(before, tree());
        verifyNoInteractions(scanner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foldersGetContractBindsOptionalNamedPathAndPreservesExactResponseFields(boolean withPath) throws Exception {
        StorageDirectoryRegistry service = mock(StorageDirectoryRegistry.class);
        String path = withPath ? sandbox.resolve("服务器 disk").toString() : null;
        FolderListing listing = new FolderListing(path, withPath ? sandbox.toString() : null, false,
                withPath ? "目录不可登记" : null, List.of(new FolderEntry("child", sandbox.resolve("child").toString(), true, null)));
        when(service.browseForAdmin(path)).thenReturn(listing);
        var mvc = standaloneSetup(new StorageDirectoryController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        var request = get("/api/b/storage/folders");
        if (withPath) {
            request.param("path", path);
        }
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.data.path").value(path))
                .andExpect(jsonPath("$.data.parentPath").value(listing.parentPath()))
                .andExpect(jsonPath("$.data.selectable").value(false))
                .andExpect(jsonPath("$.data.disabledReason").value(listing.disabledReason()))
                .andExpect(jsonPath("$.data.directories[0]", org.hamcrest.Matchers.aMapWithSize(4)))
                .andExpect(jsonPath("$.data.directories[0].name").value("child"))
                .andExpect(jsonPath("$.data.directories[0].path").value(sandbox.resolve("child").toString()))
                .andExpect(jsonPath("$.data.directories[0].enterable").value(true))
                .andExpect(jsonPath("$.data.directories[0].disabledReason").value(org.hamcrest.Matchers.nullValue()));
        verify(service).browseForAdmin(path);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(db.jdbc, transactions, scanner);
    }

    @ParameterizedTest
    @CsvSource({"anonymous,401", "MEMBER,403", "admin,403"})
    void foldersGetEnforcesRealRegistryAuthorizationBeforeAnyIo(String role, int httpStatus) throws Exception {
        if (!role.equals("anonymous")) {
            CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(7L, "test-user", role, java.util.Set.of()));
        }
        var mvc = standaloneSetup(new StorageDirectoryController(registry)).setControllerAdvice(new GlobalExceptionHandler()).build();
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            throw new AssertionError("Unauthorized GET must not access filesystem");
        })) {
            mvc.perform(get("/api/b/storage/folders")).andExpect(status().is(httpStatus));
            files.verifyNoInteractions();
        }
        verifyNoInteractions(db.jdbc, transactions, scanner);
    }

    @Test
    void foldersGetRejectsBlankPathAndDoesNotProvideAMkdirEndpoint() throws Exception {
        admin();
        var mvc = standaloneSetup(new StorageDirectoryController(registry)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/b/storage/folders").param("path", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST));
        mvc.perform(get("/api/b/storage/folders").param("path", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST));
        mvc.perform(post("/api/b/storage/folders")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(db.jdbc, transactions, scanner);
    }

    private void assertBrowseOnlyReads() {
        assertTrue(db.mutations.isEmpty());
        assertTrue(db.queries.stream().allMatch(sql -> sql.equals(JdbcFixture.SELECT + " ORDER BY id")));
        verifyNoInteractions(transactions, scanner);
    }

    private static MockedStatic<Files> readOnlyBrowseFiles(Set<Path> streamRoots) {
        return mockStatic(Files.class, invocation -> browseFileCall(invocation, streamRoots));
    }

    private static Object browseFileCall(InvocationOnMock invocation, Set<Path> streamRoots) throws Throwable {
        // Mockito also intercepts JDK-private helpers (provider/followLinks). Public entry points
        // below still reject every content read, write and recursive traversal before those helpers.
        if (!java.lang.reflect.Modifier.isPublic(invocation.getMethod().getModifiers())) {
            return invocation.callRealMethod();
        }
        String method = invocation.getMethod().getName();
        if (method.equals("newDirectoryStream")) {
            assertTrue(streamRoots.contains(invocation.getArgument(0)),
                    () -> "Must not enumerate children, registered trees or host directories: " + invocation.getArgument(0));
        } else if (!Set.of("readAttributes", "exists", "isDirectory", "isSymbolicLink", "isReadable", "isWritable",
                "isExecutable", "getFileStore", "isSameFile").contains(method)) {
            throw new AssertionError("Browsing must not write, read contents or recursively scan: " + method);
        }
        return invocation.callRealMethod();
    }

    private StorageDirectoryRegistry registryWith(String configuredRoot) {
        return new StorageDirectoryRegistry(db.jdbc, transactions, configuredRoot, scanner);
    }

    private Row readyRow(long id, String name, boolean isDefault) throws IOException {
        Path root = Files.createDirectories(sandbox.resolve(name));
        Files.createDirectories(privateRoot(root));
        Row row = new Row(id, root.toString(), UUID.randomUUID().toString(), isDefault);
        row.ready = true;
        db.rows.put(id, row);
        Files.writeString(marker(root), row.marker);
        Files.writeString(marker(privateRoot(root)), row.marker);
        return row;
    }

    private void damage(Row row, boolean privateSide, String failure) throws IOException {
        Path root = side(row, privateSide);
        if (failure.equals("pending")) {
            row.ready = false;
            return;
        }
        Files.delete(marker(root));
        switch (failure) {
            case "missing-marker" -> { }
            case "missing-root" -> Files.delete(root);
            case "foreign-marker" -> Files.writeString(marker(root), UUID.randomUUID().toString());
            case "short-marker" -> Files.writeString(marker(root), "broken");
            case "long-marker" -> Files.writeString(marker(root), row.marker + "\n");
            case "directory-marker" -> Files.createDirectory(marker(root));
            case "symlink-marker" -> {
                Path target = Files.writeString(sandbox.resolve("marker-link-target"), row.marker);
                Files.createSymbolicLink(marker(root), target);
            }
            default -> throw new AssertionError(failure);
        }
    }

    private String invalidInput(String form) {
        if (form == null || form.isEmpty()) {
            return form;
        }
        return switch (form) {
            case "blank" -> "   ";
            case "relative" -> "relative-path";
            case "dot" -> sandbox + "/./new-root";
            case "dotdot" -> sandbox + "/child/../new-root";
            case "double-slash" -> sandbox + "//new-root";
            case "trailing-slash" -> sandbox + "/new-root/";
            case "filesystem-root" -> sandbox.getRoot().toString();
            case "nul" -> sandbox + "/bad\0path";
            case "too-long" -> sandbox + "/" + "x".repeat(1025);
            default -> throw new AssertionError(form);
        };
    }

    private static Path privateRoot(Path root) {
        return root.resolveSibling(root.getFileName() + "-private");
    }

    private static Path side(Row row, boolean privateSide) {
        return privateSide ? privateRoot(row.root()) : row.root();
    }

    private static Path marker(Path root) {
        return root.resolve(MARKER);
    }

    private static void admin() {
        CurrentUserHolder.set(new CurrentUserHolder.CurrentUser(1L, "test-admin", CurrentUserHolder.ROLE_ADMIN,
                java.util.Set.of(com.familyhome.common.auth.PermissionCatalog.STORAGE_ACCESS)));
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class, action).getCode());
    }

    private static void assertMarkers(Row row) throws IOException {
        assertEquals(row.marker, Files.readString(marker(row.root())));
        assertEquals(row.marker, Files.readString(marker(privateRoot(row.root()))));
    }

    private static void assertOnlyMarker(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            assertEquals(List.of(MARKER), entries.map(path -> path.getFileName().toString()).toList());
        }
    }

    private static void assertSpaceNearSamples(Long actual, long before, long after) {
        assertNotNull(actual);
        // The real shared filesystem can change while the test runs; tolerate 16 MiB of other I/O.
        long slack = 16L * 1024 * 1024;
        assertTrue(actual >= Math.min(before, after) - slack && actual <= Math.max(before, after) + slack,
                () -> "Expected real FileStore bytes near " + before + " / " + after + ", got " + actual);
    }

    private Map<String, String> tree() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(sandbox)) {
            for (Path path : paths.sorted().toList()) {
                String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory"
                        : "file:" + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                result.put(sandbox.relativize(path).toString(), value);
            }
        }
        return result;
    }

    @FunctionalInterface
    private interface CommitAction {
        void run() throws IOException;
    }

    private static final class Row {
        final long id;
        String path;
        final String marker;
        boolean isDefault;
        boolean ready;
        String publicIdentity;
        String privateIdentity;

        Row(long id, String path, String marker, boolean isDefault) {
            this.id = id;
            this.path = path;
            this.marker = marker;
            this.isDefault = isDefault;
        }

        Path root() {
            return Path.of(path);
        }
    }

    /** Deliberately small SQL fixture, not a database emulator. Unexpected calls fail the test. */
    private static final class JdbcFixture {
        static final String SELECT = "SELECT id, path, marker, is_default, initialization_state, "
                + "public_identity, private_identity FROM file_storage_directory";
        final Map<Long, Row> rows = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>();
        final List<String> mutations = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final JdbcTemplate jdbc = mock(JdbcTemplate.class, this::answer);

        private Object answer(InvocationOnMock invocation) throws Exception {
            Object[] args = invocation.getArguments();
            String method = invocation.getMethod().getName();
            if (method.equals("query") && args[0] instanceof String sql && args[1] instanceof RowMapper<?> mapper) {
                queries.add(sql);
                List<Row> selected;
                if (sql.equals("SELECT id FROM file_storage_directory WHERE id = 1 FOR UPDATE")) {
                    selected = rows.containsKey(1L) ? List.of(rows.get(1L)) : List.of();
                } else if (sql.equals(SELECT + " ORDER BY id")) {
                    selected = rows.values().stream().sorted(java.util.Comparator.comparingLong(row -> row.id)).toList();
                } else if (sql.equals(SELECT + " WHERE id = ?")) {
                    Row row = rows.get(((Number) args[2]).longValue());
                    selected = row == null ? List.of() : List.of(row);
                } else if (sql.equals(SELECT + " WHERE is_default = 1")) {
                    selected = rows.values().stream().filter(row -> row.isDefault).toList();
                } else {
                    throw new AssertionError("Unexpected query: " + sql);
                }
                List<Object> mapped = new ArrayList<>();
                for (Row row : selected) {
                    mapped.add(mapper.mapRow(resultSet(row), mapped.size()));
                }
                return mapped;
            }
            if (method.equals("update") && args[0] instanceof PreparedStatementCreator creator) {
                return insertPending(creator, (KeyHolder) args[1]);
            }
            if (method.equals("update") && args[0] instanceof String sql) {
                mutations.add(sql);
                if (sql.startsWith("INSERT INTO file_storage_directory (id, path, marker, is_default)")) {
                    rows.put(1L, new Row(1, (String) args[1], (String) args[2], true));
                    events.add("pending:1");
                } else if (sql.equals("UPDATE file_storage_directory SET public_identity = ?, private_identity = ? "
                        + "WHERE id = 1 AND initialization_state = 'PENDING'")) {
                    rows.get(1L).publicIdentity = (String) args[1];
                    rows.get(1L).privateIdentity = (String) args[2];
                } else if (sql.equals("UPDATE file_storage_directory SET initialization_state = 'READY' WHERE id = ?")) {
                    long id = ((Number) args[1]).longValue();
                    rows.get(id).ready = true;
                    events.add("ready:" + id);
                } else if (sql.equals("UPDATE file_storage_directory SET is_default = 0 WHERE is_default = 1")) {
                    rows.values().forEach(row -> row.isDefault = false);
                } else if (sql.equals("UPDATE file_storage_directory SET is_default = 1 WHERE id = ?")) {
                    rows.get(((Number) args[1]).longValue()).isDefault = true;
                } else {
                    throw new AssertionError("Unexpected update: " + sql);
                }
                return 1;
            }
            throw new AssertionError("Unexpected JDBC method: " + invocation.getMethod());
        }

        private int insertPending(PreparedStatementCreator creator, KeyHolder keys) throws Exception {
            Map<Integer, String> parameters = new LinkedHashMap<>();
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(invocation -> {
                parameters.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(statement).setString(anyInt(), nullable(String.class));
            Connection connection = mock(Connection.class);
            String sql = "INSERT INTO file_storage_directory "
                    + "(path, marker, public_identity, private_identity) VALUES (?, ?, ?, ?)";
            when(connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)).thenReturn(statement);
            assertSame(statement, creator.createPreparedStatement(connection));
            long id = rows.keySet().stream().mapToLong(Long::longValue).max().orElse(0) + 1;
            Row row = new Row(id, parameters.get(1), parameters.get(2), false);
            row.publicIdentity = parameters.get(3);
            row.privateIdentity = parameters.get(4);
            rows.put(id, row);
            keys.getKeyList().add(Map.of("id", id));
            mutations.add(sql);
            events.add("pending:" + id);
            return 1;
        }

        private ResultSet resultSet(Row row) {
            return mock(ResultSet.class, invocation -> switch (invocation.getMethod().getName()) {
                case "getLong" -> {
                    Object column = invocation.getArgument(0);
                    assertTrue(column.equals("id") || column.equals(1));
                    yield row.id;
                }
                case "getBoolean" -> {
                    assertEquals("is_default", invocation.getArgument(0));
                    yield row.isDefault;
                }
                case "getString" -> switch ((String) invocation.getArgument(0)) {
                    case "path" -> row.path;
                    case "marker" -> row.marker;
                    case "initialization_state" -> row.ready ? "READY" : "PENDING";
                    case "public_identity" -> row.publicIdentity;
                    case "private_identity" -> row.privateIdentity;
                    default -> throw new AssertionError("Unexpected result column");
                };
                default -> throw new AssertionError("Unexpected ResultSet method: " + invocation.getMethod());
            });
        }
    }
}
