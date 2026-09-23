package com.familyhome.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.common.auth.SessionToken;
import com.familyhome.common.crypto.TransportCipher;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.biz.config.FileStorageConfig;
import com.familyhome.user.biz.manager.UserPasswordManager;
import com.familyhome.vault.biz.manager.VaultCipherManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real HTTP/MySQL 8 regression tests. Apply migrations separately before running:
 * this context deliberately disables Flyway and never repairs schema or historical data.
 * Each invocation creates exactly two users and owns a random, SQL-LIKE-safe prefix.
 * No surrounding transaction: HTTP workers must see committed fixtures.
 * Secrets are synthetic; assertions never print their plaintext or ciphertext.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.com.familyhome=INFO",
        "spring.flyway.enabled=false",
        "fh.storage.root=target/partition-test-files",
        "fh.storage.url-prefix=/files"
})
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "FH_RUN_DB_TESTS", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class VaultDocumentPartitionIntegrationTest {
    private static final String VAULT = "/api/b/vault/accounts";
    private static final String CATEGORIES = "/api/b/file/categories";
    private static final String DOCUMENTS = "/api/b/file/documents";
    private static final String VALIDATION = "/api/b/validation/duplicate";
    private static final int CONTENDERS = 4;

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransportCipher transport;
    @Autowired private VaultCipherManager vaultCipher;
    @Autowired private UserPasswordManager passwords;
    @Autowired private FileFacade files;
    @Autowired private FileStorageConfig storageConfig;
    @Autowired private SessionToken sessionToken;

    private final String prefix = "vp" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-";
    private final Set<Path> ownedPaths = new LinkedHashSet<>();
    private final List<byte[]> uploadedPayloads = new ArrayList<>();
    private Set<Path> initialPaths = Set.of();
    private Path publicRoot;
    private Path privateRoot;
    private boolean localDatabaseVerified;
    private boolean storageVerified;
    private long adminId;
    private long memberId;
    private String secret;
    private String transportSecret;
    private String storedSecret;

    enum Partition {
        PUBLIC("PUBLIC"), PRIVATE_A("PRIVATE"), PRIVATE_B("PRIVATE");
        final String scope;
        Partition(String scope) { this.scope = scope; }
    }

    enum Domain {
        VAULT_ACCOUNT(VAULT, "vault_account", "VAULT_ACCOUNT_DUPLICATED"),
        FILE_CATEGORY(CATEGORIES, "file_category", "FILE_CATEGORY_NAME_DUPLICATED");
        final String path;
        final String table;
        final String conflictCode;
        Domain(String path, String table, String conflictCode) {
            this.path = path;
            this.table = table;
            this.conflictCode = conflictCode;
        }
    }

    @BeforeEach
    void verifyLocalEnvironmentThenCreateTwoActors() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata = connection.getMetaData();
            assertEquals("MySQL", metadata.getDatabaseProductName());
            assertEquals(8, metadata.getDatabaseMajorVersion());
            String url = metadata.getURL();
            assertTrue(url.startsWith("jdbc:mysql:"));
            String host = URI.create(url.substring("jdbc:".length())).getHost();
            assertTrue(host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host),
                    "Refusing fixture writes outside local MySQL");
        }
        localDatabaseVerified = true;
        publicRoot = Path.of(storageConfig.getRoot()).toAbsolutePath().normalize();
        assertEquals(Path.of("target/partition-test-files").toAbsolutePath().normalize(), publicRoot);
        privateRoot = publicRoot.resolveSibling(publicRoot.getFileName() + "-private");
        assertFalse(Files.isSymbolicLink(publicRoot));
        assertFalse(Files.isSymbolicLink(privateRoot));
        initialPaths = diskPaths();
        storageVerified = true;
        // Fail before any fixture writes when the parent has not applied V106/V404 yet.
        for (String table : List.of("vault_account", "file_category", "file_object")) {
            assertEquals(2L, count("SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME IN ('scope','owner_id')", table),
                    "Partition migrations must already be applied: " + table);
        }
        secret = UUID.randomUUID().toString();
        transportSecret = transport.encrypt(secret);
        storedSecret = vaultCipher.encrypt(secret);
        String hash = passwords.hash(UUID.randomUUID().toString());
        adminId = insert("app_user", Map.of("name", n("admin"), "phone", n("phone-a"),
                "password_hash", hash, "role", "ADMIN"));
        memberId = insert("app_user", Map.of("name", n("member"), "phone", n("phone-b"),
                "password_hash", hash, "role", "MEMBER"));
    }

    @AfterEach
    void cleanOnlyThisInvocationsRowsAndPhysicalFiles() {
        if (!localDatabaseVerified) {
            return;
        }
        List<Executable> cleanup = new ArrayList<>();
        cleanup.add(() -> {
            if (storageVerified) {
                // Include failed response writes and tombstones, never historical rows.
                for (Map<String, Object> row : jdbc.queryForList(
                        "SELECT file_key, thumb_key FROM file_object WHERE origin_name LIKE ?", prefix + "%")) {
                    rememberKey((String) row.get("file_key"));
                    rememberKey((String) row.get("thumb_key"));
                }
            }
        });
        cleanup.add(() -> {
            if (storageVerified) {
                // A transaction may roll back after writing bytes. Inspect only NEW files in
                // the dedicated test roots, and claim only an exact invocation-owned payload.
                // Initial inventory is never read/deleted, even if it is a past test leftover.
                for (Path candidate : diskPaths()) {
                    if (!initialPaths.contains(candidate) && !ownedPaths.contains(candidate)) {
                        long size = Files.size(candidate);
                        if (uploadedPayloads.stream().anyMatch(bytes -> bytes.length == size)) {
                            byte[] actual = Files.readAllBytes(candidate);
                            if (uploadedPayloads.stream().anyMatch(bytes -> Arrays.equals(bytes, actual))) {
                                ownedPaths.add(candidate);
                            }
                        }
                    }
                }
            }
        });
        cleanup.add(() -> assertAll("Delete only owned test files", ownedPaths.stream()
                .map(path -> (Executable) () -> {
                    assertTrue(path.startsWith(publicRoot) || path.startsWith(privateRoot));
                    assertFalse(initialPaths.contains(path), "Never delete an earlier invocation's file");
                    Files.deleteIfExists(path);
                })));
        for (String sql : List.of(
                "DELETE FROM album_image_group_rel WHERE group_id IN (SELECT id FROM album_group WHERE TRIM(name) LIKE ?)",
                "DELETE FROM album_image WHERE file_id IN (SELECT id FROM file_object WHERE origin_name LIKE ?)",
                "DELETE FROM album_group WHERE TRIM(name) LIKE ?",
                "DELETE FROM recipe_image WHERE recipe_id IN (SELECT id FROM recipe WHERE TRIM(name) LIKE ?)",
                "DELETE FROM recipe_category_rel WHERE recipe_id IN (SELECT id FROM recipe WHERE TRIM(name) LIKE ?)",
                "DELETE FROM recipe WHERE TRIM(name) LIKE ?",
                "DELETE FROM recipe_category WHERE TRIM(name) LIKE ?",
                "DELETE FROM file_object WHERE origin_name LIKE ?",
                "DELETE FROM file_category WHERE TRIM(name) LIKE ?",
                "DELETE FROM vault_account WHERE TRIM(name) LIKE ?",
                "DELETE FROM app_user WHERE TRIM(name) LIKE ?")) {
            cleanup.add(() -> jdbc.update(sql, prefix + "%"));
        }
        // Attempt all cleanup even after a test/cleanup assertion fails. Do not purge directories.
        assertAll("Clean up this invocation only", cleanup);
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void sameNameAllowedInThreePartitionsButConflictsWithinEach(Domain domain) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Partition partition : Partition.values()) {
            precheck(actor(partition), domain, partition.scope, n("Café"), false, null);
            long id = create(domain, actor(partition), partition.scope, " " + n("Café") + " ");
            assertTrue(ids.add(id));
            assertPartition(domain.table, id, partition);
            assertEquals(n("Café"), column(domain.table, id, "name"));
            precheck(actor(partition), domain, partition.scope, " " + n("CAFE") + " ", true, null);
            precheck(actor(partition), domain, partition.scope, n("CAFE"), false, id);
            conflict(post(actor(partition), scoped(domain.path, partition.scope), body(domain, n("CAFE"))), domain);
        }
        // Shared PUBLIC has one namespace, regardless of the creating account.
        precheck(memberId, domain, null, n("CAFE"), true, null);
        conflict(post(memberId, domain.path, body(domain, n("CAFE"))), domain);
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void excludingAnIdInAnotherPartitionCannotHideOwnDuplicate(Domain domain) {
        long shared = create(domain, adminId, "PUBLIC", n("same"));
        long own = create(domain, adminId, "PRIVATE", n("same"));
        long other = create(domain, memberId, "PRIVATE", n("same"));
        precheck(adminId, domain, "PRIVATE", n("same"), true, shared);
        precheck(adminId, domain, "PRIVATE", n("same"), true, other);
        precheck(adminId, domain, "PRIVATE", n("same"), false, own);
        precheck(memberId, domain, "PRIVATE", n("same"), true, own);
        precheck(memberId, domain, "PUBLIC", n("same"), false, shared);
        // Omission must not search PRIVATE, even for ADMIN.
        create(domain, memberId, "PRIVATE", n("privateonly"));
        precheck(adminId, domain, null, n("privateonly"), false, null);
        precheck(adminId, domain, "PRIVATE", n("privateonly"), false, null);
        precheck(memberId, domain, "PRIVATE", n("privateonly"), true, null);
    }

    @Test
    void duplicateValidationKeepsAlbumAndDocumentScopeVocabulariesSeparate() {
        for (Domain domain : Domain.values()) {
            for (String badScope : List.of("FAMILY", "PERSONAL", "INVALID")) {
                failure(post(adminId, VALIDATION, precheckBody(domain, badScope, n("x"), null)), 400);
            }
            failure(post(null, VALIDATION, precheckBody(domain, "PUBLIC", n("x"), null)), 401);
            failure(post(null, VALIDATION, precheckBody(domain, "PRIVATE", n("x"), null)), 401);
        }
        failure(post(adminId, VALIDATION, Map.of("kind", "VAULT_ACCOUNT", "scope", "PRIVATE", "name", n("x"))), 400);
        for (String scope : List.of("FAMILY", "PERSONAL")) {
            JsonNode result = ok(post(adminId, VALIDATION, Map.of("kind", "ALBUM_GROUP", "scope", scope, "name", n("unused"))));
            assertTrue(result.path("data").isBoolean());
            assertFalse(result.path("data").booleanValue());
        }
        for (String scope : List.of("PUBLIC", "PRIVATE")) {
            failure(post(adminId, VALIDATION, Map.of("kind", "ALBUM_GROUP", "scope", scope, "name", n("unused"))), 400);
        }
    }

    @Test
    void publicVaultIsSharedAndOmittedScopeSupportsCrud() {
        long id = create(Domain.VAULT_ACCOUNT, adminId, null, n("shared"));
        assertPartition("vault_account", id, Partition.PUBLIC);
        assertEquals(Set.of(id), vaultIds(memberId, null));
        assertRevealed(memberId, id, null, secret);
        ok(request(memberId, HttpMethod.PUT, VAULT + "/" + id, Map.of("name", n("renamed"), "account", n("newacct"))));
        assertEquals(n("renamed"), column("vault_account", id, "name"));
        assertRevealed(adminId, id, "PUBLIC", secret);
        ok(request(memberId, HttpMethod.DELETE, VAULT + "/" + id, null));
        failure(post(adminId, VAULT + "/" + id + "/password/reveal", null), 404);
        assertEquals(Set.of(), vaultIds(adminId, "PUBLIC"));
    }

    @ParameterizedTest
    @EnumSource(value = Partition.class, names = {"PRIVATE_A", "PRIVATE_B"})
    void privateVaultCannotBeReadEditedRevealedOrDeletedByOtherAccountEvenAdmin(Partition partition) {
        long owner = actor(partition);
        long outsider = otherActor(owner);
        long id = create(Domain.VAULT_ACCOUNT, owner, "PRIVATE", n("private"));
        assertEquals(Set.of(id), vaultIds(owner, "PRIVATE"));
        assertEquals(Set.of(), vaultIds(outsider, "PRIVATE"));
        assertEquals(Set.of(), vaultIds(owner, null));
        assertRevealed(owner, id, "PRIVATE", secret);
        failure(post(outsider, scoped(VAULT + "/" + id + "/password/reveal", "PRIVATE"), null), 404);
        failure(request(outsider, HttpMethod.PUT, scoped(VAULT + "/" + id, "PRIVATE"),
                body(Domain.VAULT_ACCOUNT, n("intrusion"))), 404);
        failure(request(outsider, HttpMethod.DELETE, scoped(VAULT + "/" + id, "PRIVATE"), null), 404);
        assertEquals(n("private"), column("vault_account", id, "name"));
        assertPartition("vault_account", id, partition);
        assertRevealed(owner, id, "PRIVATE", secret);
        ok(request(owner, HttpMethod.PUT, scoped(VAULT + "/" + id, "PRIVATE"),
                Map.of("name", n("ownedit"), "account", n("acct"))));
        ok(request(owner, HttpMethod.DELETE, scoped(VAULT + "/" + id, "PRIVATE"), null));
        failure(post(owner, scoped(VAULT + "/" + id + "/password/reveal", "PRIVATE"), null), 404);
    }

    @Test
    void vaultIdsDoNotPermitCrossScopeAccessOrDefaultScopeFallback() {
        long shared = create(Domain.VAULT_ACCOUNT, adminId, "PUBLIC", n("shared"));
        long personal = create(Domain.VAULT_ACCOUNT, adminId, "PRIVATE", n("personal"));
        for (Map.Entry<Long, String> target : Map.of(shared, "PRIVATE", personal, "PUBLIC").entrySet()) {
            long id = target.getKey();
            String path = scoped(VAULT + "/" + id, target.getValue());
            failure(request(adminId, HttpMethod.PUT, path, Map.of("name", n("forbidden"), "account", n("acct"))), 404);
            failure(request(adminId, HttpMethod.DELETE, path, null), 404);
            failure(post(adminId, scoped(VAULT + "/" + id + "/password/reveal", target.getValue()), null), 404);
        }
        failure(request(adminId, HttpMethod.PUT, VAULT + "/" + personal, Map.of("name", n("forbidden"), "account", n("acct"))), 404);
        failure(request(adminId, HttpMethod.DELETE, VAULT + "/" + personal, null), 404);
        failure(post(adminId, VAULT + "/" + personal + "/password/reveal", null), 404);
        assertRevealed(adminId, shared, "PUBLIC", secret);
        assertRevealed(adminId, personal, "PRIVATE", secret);
    }

    @ParameterizedTest
    @EnumSource(Partition.class)
    void vaultScopeAndOwnerAreServerAssignedAndCannotBeEdited(Partition partition) {
        Map<String, Object> forged = new LinkedHashMap<>(body(Domain.VAULT_ACCOUNT, n("forged")));
        forged.put("scope", partition == Partition.PUBLIC ? "PRIVATE" : "PUBLIC");
        forged.put("ownerId", otherActor(actor(partition)));
        forged.put("owner_id", otherActor(actor(partition)));
        ResponseEntity<JsonNode> creation = post(actor(partition), scoped(VAULT, partition.scope), forged);
        // Both rejecting unknown properties and ignoring them are safe DTO policies.
        long id;
        if (creation.getStatusCode().value() == 400) {
            failure(creation, 400);
            id = create(Domain.VAULT_ACCOUNT, actor(partition), partition.scope, n("forged"));
        } else {
            id = createdId(creation);
        }
        assertPartition("vault_account", id, partition);
        forged.put("name", n("forgededit"));
        safeUnknownFields(request(actor(partition), HttpMethod.PUT, scoped(VAULT + "/" + id, partition.scope), forged));
        assertPartition("vault_account", id, partition);
        assertRevealed(actor(partition), id, partition.scope, secret);
    }

    @ParameterizedTest
    @EnumSource(Partition.class)
    void vaultEditsKeepPasswordsEncryptedAndConflictingEditRollsBack(Partition partition) {
        long actor = actor(partition);
        long first = create(Domain.VAULT_ACCOUNT, actor, partition.scope, n("Café"));
        long second = create(Domain.VAULT_ACCOUNT, actor, partition.scope, n("second"));
        // Different account/platform pairs may reuse the same synthetic password.
        long otherPair = createdId(post(actor, scoped(VAULT, partition.scope), Map.of(
                "name", n("Café"), "account", n("otheracct"), "password", transportSecret)));
        assertNotEquals(first, otherPair);
        String changedSecret = UUID.randomUUID().toString();
        conflict(request(actor, HttpMethod.PUT, scoped(VAULT + "/" + second, partition.scope), Map.of(
                "name", " " + n("CAFE") + " ", "account", n("ACCT"), "password", transport.encrypt(changedSecret))), Domain.VAULT_ACCOUNT);
        assertEquals(n("second"), column("vault_account", second, "name"));
        assertRevealed(actor, second, partition.scope, secret);
        ok(request(actor, HttpMethod.PUT, scoped(VAULT + "/" + second, partition.scope),
                Map.of("name", n("edited"), "account", n("acct"))));
        assertRevealed(actor, second, partition.scope, secret);
        ok(request(actor, HttpMethod.PUT, scoped(VAULT + "/" + second, partition.scope),
                Map.of("name", n("edited"), "account", n("acct"), "password", transport.encrypt(changedSecret))));
        assertRevealed(actor, second, partition.scope, changedSecret);
        assertEquals(Set.of(first, second, otherPair), vaultIds(actor, partition.scope));
    }

    @Test
    void deletingPrivateVaultReleasesOnlyThatPartitionsUniquePair() {
        long shared = create(Domain.VAULT_ACCOUNT, adminId, "PUBLIC", n("same"));
        long own = create(Domain.VAULT_ACCOUNT, adminId, "PRIVATE", n("same"));
        long other = create(Domain.VAULT_ACCOUNT, memberId, "PRIVATE", n("same"));
        for (int cycle = 0; cycle < 2; cycle++) {
            ok(request(adminId, HttpMethod.DELETE, scoped(VAULT + "/" + own, "PRIVATE"), null));
            assertNull(column("vault_account", own, "unique_name"));
            assertNull(column("vault_account", own, "unique_account"));
            precheck(adminId, Domain.VAULT_ACCOUNT, "PRIVATE", n("same"), false, null);
            precheck(memberId, Domain.VAULT_ACCOUNT, "PRIVATE", n("same"), true, null);
            precheck(memberId, Domain.VAULT_ACCOUNT, "PUBLIC", n("same"), true, null);
            long replacement = create(Domain.VAULT_ACCOUNT, adminId, "PRIVATE", n("same"));
            assertNotEquals(own, replacement);
            own = replacement;
        }
        assertRevealed(memberId, shared, "PUBLIC", secret);
        assertRevealed(memberId, other, "PRIVATE", secret);
    }

    @Test
    void vaultPaginationAndKeywordNeverCountAnotherPartition() {
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            expected.add(create(Domain.VAULT_ACCOUNT, adminId, "PRIVATE", n("page" + i)));
        }
        create(Domain.VAULT_ACCOUNT, memberId, "PRIVATE", n("page-foreign"));
        create(Domain.VAULT_ACCOUNT, adminId, "PUBLIC", n("page-public"));
        Set<Long> seen = new LinkedHashSet<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode data = ok(get(adminId, scoped(VAULT, "PRIVATE") + "&keyword=" + n("page")
                    + "&pageNo=" + page + "&pageSize=1")).path("data");
            assertEquals(3L, data.path("total").asLong());
            assertEquals(page, data.path("pageNo").asInt());
            assertEquals(1, data.path("pageSize").asInt());
            assertEquals(page < 3, data.path("hasMore").asBoolean());
            assertNoPasswords(data.path("list"));
            assertEquals(1, data.path("list").size());
            assertTrue(seen.add(data.path("list").get(0).path("id").asLong()));
        }
        assertEquals(expected, seen);
        long accountMatch = createdId(post(adminId, scoped(VAULT, "PRIVATE"), Map.of(
                "name", n("platform"), "account", n("search-account"), "password", transportSecret)));
        JsonNode found = ok(get(adminId, scoped(VAULT, "PRIVATE") + "&keyword=" + n("search-account"))).path("data");
        assertEquals(1L, found.path("total").asLong());
        assertEquals(accountMatch, found.path("list").get(0).path("id").asLong());
        assertNoPasswords(found.path("list"));
    }

    @ParameterizedTest
    @EnumSource(Partition.class)
    void categoryBodyCannotForgePartitionOrOwner(Partition partition) {
        Map<String, Object> body = Map.of("name", n("category"),
                "scope", partition == Partition.PUBLIC ? "PRIVATE" : "PUBLIC",
                "ownerId", otherActor(actor(partition)), "owner_id", otherActor(actor(partition)));
        ResponseEntity<JsonNode> response = post(actor(partition), scoped(CATEGORIES, partition.scope), body);
        long id;
        if (response.getStatusCode().value() == 400) {
            failure(response, 400);
            id = category(partition, "category");
        } else {
            id = createdId(response);
        }
        assertPartition("file_category", id, partition);
    }

    @Test
    void categoryAndDocumentListsExposeOnlySelectedPartition() {
        Map<Partition, Long> categories = new LinkedHashMap<>();
        Map<Partition, Long> documents = new LinkedHashMap<>();
        for (Partition partition : Partition.values()) {
            long category = category(partition, "same");
            categories.put(partition, category);
            documents.put(partition, upload(partition, category, n("same.csv"), payload("list-" + partition)).path("id").asLong());
        }
        for (Partition partition : Partition.values()) {
            assertEquals(Set.of(categories.get(partition)), fixtureIds(ok(get(actor(partition), scoped(CATEGORIES, partition.scope))).path("data")));
            JsonNode list = ok(get(actor(partition), scoped(DOCUMENTS, partition.scope))).path("data");
            assertEquals(Set.of(documents.get(partition)), fixtureIds(list));
            assertDocumentUrls(list, partition);
            assertEquals(Set.of(documents.get(partition)), fixtureIds(ok(get(actor(partition), scoped(DOCUMENTS, partition.scope)
                    + "&categoryId=" + categories.get(partition))).path("data")));
        }
        assertEquals(Set.of(categories.get(Partition.PUBLIC)), fixtureIds(ok(get(memberId, CATEGORIES)).path("data")));
        assertEquals(Set.of(documents.get(Partition.PUBLIC)), fixtureIds(ok(get(memberId, DOCUMENTS)).path("data")));
    }

    @ParameterizedTest
    @EnumSource(Partition.class)
    void foreignCategoryFilterAndUploadReturn404BeforeWritingFiles(Partition selected) throws Exception {
        Map<Partition, Long> categories = new LinkedHashMap<>();
        for (Partition partition : Partition.values()) {
            categories.put(partition, category(partition, "category"));
        }
        for (Partition target : Partition.values()) {
            if (target == selected) {
                continue;
            }
            String path = scoped(DOCUMENTS, selected.scope);
            failure(get(actor(selected), path + "&categoryId=" + categories.get(target)), 404);
            Set<Path> before = diskPaths();
            long beforeRows = ownFileCount();
            failure(uploadRequest(actor(selected), selected.scope, categories.get(target), n("rejected.csv"), payload("rejected")), 404);
            assertEquals(beforeRows, ownFileCount());
            assertEquals(before, diskPaths(), "Category authorization must happen before storage writes");
        }
        if (selected != Partition.PUBLIC) {
            failure(get(actor(selected), DOCUMENTS + "?categoryId=" + categories.get(selected)), 404);
            Set<Path> before = diskPaths();
            failure(uploadRequest(actor(selected), null, categories.get(selected), n("default-rejected.csv"), payload("default-rejected")), 404);
            assertEquals(before, diskPaths());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Partition.class, names = {"PRIVATE_A", "PRIVATE_B"})
    void privateDownloadsUseSiblingRootAndRejectStaticAndOtherAccountAccess(Partition partition) throws Exception {
        byte[] bytes = payload("private-download");
        JsonNode document = upload(partition, category(partition, "private"), n("私密 report.csv"), bytes);
        long id = document.path("id").asLong();
        assertTrue(document.path("url").isNull(), "PRIVATE VO.url must be explicit null");
        String key = fileKey(id);
        assertTrue(key.startsWith("private-documents/" + actor(partition) + "/"));
        assertFalse(Files.exists(publicRoot.resolve(key)));
        Path storedPath = physicalPath(key);
        assertTrue(storedPath.startsWith(privateRoot));
        assertArrayEquals(bytes, Files.readAllBytes(storedPath));
        assertDownload(actor(partition), id, "PRIVATE", document.path("name").asText(), bytes);
        assertEquals(404, raw(null, "/files/" + key).getStatusCode().value());
        // Also deny a deliberately misplaced, invocation-owned copy under the public root.
        // Otherwise a static 404 caused only by an absent file would not test the deny rule.
        Path misplaced = publicRoot.resolve(key);
        assertTrue(ownedPaths.contains(misplaced));
        Files.createDirectories(misplaced.getParent());
        Files.write(misplaced, bytes);
        assertEquals(404, raw(null, "/files/" + key).getStatusCode().value());
        assertEquals(404, raw(actor(partition), "/files/" + key).getStatusCode().value());
        assertEquals(404, raw(otherActor(actor(partition)), downloadPath(id, "PRIVATE")).getStatusCode().value());
        assertEquals(404, raw(actor(partition), downloadPath(id, "PUBLIC")).getStatusCode().value());
        assertEquals(404, raw(actor(partition), downloadPath(id, null)).getStatusCode().value());
        failure(request(otherActor(actor(partition)), HttpMethod.DELETE, scoped(DOCUMENTS + "/" + id, "PRIVATE"), null), 404);
        failure(request(actor(partition), HttpMethod.DELETE, DOCUMENTS + "/" + id, null), 404);
        assertDownload(actor(partition), id, "PRIVATE", document.path("name").asText(), bytes);
        ok(request(actor(partition), HttpMethod.DELETE, scoped(DOCUMENTS + "/" + id, "PRIVATE"), null));
        assertFalse(Files.exists(storedPath));
        assertEquals(404, raw(actor(partition), downloadPath(id, "PRIVATE")).getStatusCode().value());
        assertEquals(404, raw(null, "/files/" + key).getStatusCode().value());
    }

    @Test
    void publicDocumentsRemainSharedWithStaticUrlAndDefaultScope() throws Exception {
        long category = create(Domain.FILE_CATEGORY, adminId, null, n("shared"));
        byte[] bytes = payload("public-download");
        JsonNode document = ok(uploadRequest(adminId, null, category, n("public.csv"), bytes)).path("data");
        long id = document.path("id").asLong();
        rememberFile(id);
        assertPartition("file_category", category, Partition.PUBLIC);
        assertPartition("file_object", id, Partition.PUBLIC);
        String url = document.path("url").asText();
        assertEquals("/files/" + fileKey(id), url);
        ResponseEntity<byte[]> staticResponse = raw(null, url);
        assertEquals(200, staticResponse.getStatusCode().value());
        assertArrayEquals(bytes, staticResponse.getBody());
        assertDownload(memberId, id, null, document.path("name").asText(), bytes);
        assertEquals(404, raw(adminId, downloadPath(id, "PRIVATE")).getStatusCode().value());
        failure(request(adminId, HttpMethod.DELETE, scoped(DOCUMENTS + "/" + id, "PRIVATE"), null), 404);
        ok(request(memberId, HttpMethod.DELETE, DOCUMENTS + "/" + id, null));
        assertFalse(Files.exists(publicRoot.resolve(fileKey(id))));
        assertEquals(404, raw(null, url).getStatusCode().value());
        assertEquals(404, raw(adminId, downloadPath(id, "PUBLIC")).getStatusCode().value());
    }

    @ParameterizedTest
    @EnumSource(value = Partition.class, names = {"PUBLIC", "PRIVATE_A"})
    void sameBytesAndFilenameGetIndependentIdsAndPathsWithReuseOnlyInsideProtectionPartition(Partition firstPartition) throws Exception {
        byte[] bytes = payload("identical");
        Map<Partition, List<Long>> ids = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<Long> uniqueIds = new LinkedHashSet<>();
        Set<String> md5s = new LinkedHashSet<>();
        // Exercise both PUBLIC -> PRIVATE and PRIVATE -> other owner/PUBLIC lookup order.
        List<Partition> order = firstPartition == Partition.PUBLIC
                ? List.of(Partition.PUBLIC, Partition.PRIVATE_A, Partition.PRIVATE_B)
                : List.of(Partition.PRIVATE_A, Partition.PRIVATE_B, Partition.PUBLIC);
        for (Partition partition : order) {
            long category = category(partition, "same");
            long first = upload(partition, category, n("same.csv"), bytes).path("id").asLong();
            long secondActor = partition == Partition.PUBLIC ? memberId : actor(partition);
            long second = ok(uploadRequest(secondActor, partition.scope, category, n("same.csv"), bytes)).path("data").path("id").asLong();
            rememberFile(second);
            ids.put(partition, List.of(first, second));
            assertEquals(0, ((Number) column("file_object", first, "hard_link")).intValue(), "First upload in a partition must not reuse another partition");
            assertEquals(1, ((Number) column("file_object", second, "hard_link")).intValue(), "Same-partition upload should use MD5 reuse");
            for (long id : List.of(first, second)) {
                assertTrue(uniqueIds.add(id));
                assertTrue(keys.add(fileKey(id)));
                assertPartition("file_object", id, partition);
                md5s.add((String) column("file_object", id, "md5"));
                assertArrayEquals(bytes, Files.readAllBytes(physicalPath(fileKey(id))));
            }
        }
        assertEquals(1, md5s.size());
        for (Partition left : Partition.values()) {
            for (Partition right : Partition.values()) {
                if (left != right) {
                    assertFalse(Files.isSameFile(physicalPath(fileKey(ids.get(left).get(0))), physicalPath(fileKey(ids.get(right).get(0)))),
                            "Different protection partitions must not share an inode");
                }
            }
        }
        for (Partition partition : Partition.values()) {
            long first = ids.get(partition).get(0);
            ok(request(actor(partition), HttpMethod.DELETE, scoped(DOCUMENTS + "/" + first, partition.scope), null));
            assertFalse(Files.exists(physicalPath(fileKey(first))));
            assertEquals(404, raw(actor(partition), downloadPath(first, partition.scope)).getStatusCode().value());
            // Deleting a linked path must not invalidate ANY surviving partition's copy.
            for (Partition survivor : Partition.values()) {
                assertDownload(actor(survivor), ids.get(survivor).get(1), survivor.scope, n("same.csv"), bytes);
            }
        }
    }

    @Test
    void documentsAreExcludedFromGenericFileFacadeAndNeverExposedAsAlbumOrRecipeImages() throws Exception {
        List<Long> documentIds = new ArrayList<>();
        for (Partition partition : Partition.values()) {
            documentIds.add(upload(partition, category(partition, "cat"), n("doc.csv"), payload("doc-" + partition)).path("id").asLong());
        }
        byte[] png = pngPayload();
        JsonNode image = ok(multipart(adminId, "/api/b/file/upload", n("control.png"), png, "image/png", Map.of("bizType", "ALBUM_IMAGE"))).path("data");
        long imageId = image.path("id").asLong();
        assertTrue(imageId > 0);
        rememberFile(imageId);
        List<Long> all = new ArrayList<>(documentIds);
        all.add(imageId);
        assertEquals(Set.of(imageId), files.mapByIds(all).keySet(), "Generic image lookup must exclude even PUBLIC documents");
        // PERSONAL confines any unexpected city recalculation to our newly created account.
        long group = createdId(post(adminId, "/api/b/album/groups", Map.of("name", n("bind-group"), "scope", "PERSONAL")));
        long recipeCategory = createdId(post(adminId, "/api/b/recipe/categories", Map.of("name", n("recipe-cat"))));
        assertOwnedRow("album_group", group);
        assertOwnedRow("recipe_category", recipeCategory);
        for (long documentId : documentIds) {
            clientFailure(request(adminId, HttpMethod.POST, "/api/b/album/groups/" + group + "/images?scope=PERSONAL",
                    Map.of("items", List.of(Map.of("fileId", documentId)))));
            // 菜谱既有写入口不校验图片 ID；本轮的边界是在共用映射处拒绝文档 URL，
            // 不把另一个域的写合同变更塞进这轮隔离测试。
            long recipeId = createdId(post(adminId, "/api/b/recipe/recipes", Map.of("name", n("bind-recipe-" + documentId),
                    "categoryId", recipeCategory, "coverFileIds", List.of(documentId))));
            JsonNode recipe = ok(request(adminId, HttpMethod.GET, "/api/b/recipe/recipes/" + recipeId, null)).path("data");
            assertTrue(recipe.path("coverUrl").isMissingNode() || recipe.path("coverUrl").isNull());
            assertEquals(0L, count("SELECT COUNT(*) FROM album_image WHERE file_id = ?", documentId));
        }
        assertEquals((long) documentIds.size(), count("SELECT COUNT(*) FROM recipe WHERE name LIKE ?", n("bind-recipe-") + "%"));
        assertEquals(0L, count("SELECT COUNT(*) FROM album_image_group_rel WHERE group_id = ?", group));
    }

    @Test
    void ordinaryImageUploadCannotCreateDocumentBusinessType() throws Exception {
        byte[] png = pngPayload();
        Set<Path> before = diskPaths();
        long rows = ownFileCount();
        failure(multipart(adminId, "/api/b/file/upload", n("forbidden.png"), png, "image/png", Map.of("bizType", "document")), 400);
        assertEquals(rows, ownFileCount());
        assertEquals(before, diskPaths(), "Reject document bizType before image/storage processing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "PRIVATE", "DEFAULT"})
    void everyVaultCategoryAndDocumentEndpointRequiresIdentity(String selection) throws Exception {
        String scope = "DEFAULT".equals(selection) ? null : selection;
        Partition partition = "PRIVATE".equals(scope) ? Partition.PRIVATE_A : Partition.PUBLIC;
        long vault = create(Domain.VAULT_ACCOUNT, adminId, scope, n("auth"));
        long category = category(partition, "auth");
        long document = upload(partition, category, n("auth.csv"), payload("auth")).path("id").asLong();
        failure(get(null, scoped(VAULT, scope)), 401);
        failure(post(null, scoped(VAULT, scope), body(Domain.VAULT_ACCOUNT, n("noauth"))), 401);
        failure(request(null, HttpMethod.PUT, scoped(VAULT + "/" + vault, scope),
                Map.of("name", n("noauth"), "account", n("acct"))), 401);
        failure(request(null, HttpMethod.DELETE, scoped(VAULT + "/" + vault, scope), null), 401);
        failure(post(null, scoped(VAULT + "/" + vault + "/password/reveal", scope), null), 401);
        failure(get(null, scoped(CATEGORIES, scope)), 401);
        failure(post(null, scoped(CATEGORIES, scope), Map.of("name", n("noauth"))), 401);
        failure(get(null, scoped(DOCUMENTS, scope)), 401);
        Set<Path> before = diskPaths();
        failure(uploadRequest(null, scope, category, n("noauth.csv"), payload("noauth")), 401);
        assertEquals(before, diskPaths());
        failure(request(null, HttpMethod.DELETE, scoped(DOCUMENTS + "/" + document, scope), null), 401);
        assertEquals(401, raw(null, downloadPath(document, scope)).getStatusCode().value());
        for (Domain domain : Domain.values()) {
            failure(post(null, VALIDATION, precheckBody(domain, scope, n("auth"), null)), 401);
        }
        assertRevealed(adminId, vault, scope, secret);
        assertDownload(adminId, document, scope, n("auth.csv"), payload("auth"));
        assertEquals(0L, count("SELECT COUNT(*) FROM file_category WHERE name = ?", n("noauth")));
        assertEquals(0L, count("SELECT COUNT(*) FROM vault_account WHERE name = ?", n("noauth")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAMILY", "PERSONAL", "INVALID", "public", "private"})
    void invalidHttpScopesNeverFallBackToPublic(String scope) throws Exception {
        long vault = create(Domain.VAULT_ACCOUNT, adminId, "PUBLIC", n("valid"));
        long category = category(Partition.PUBLIC, "valid");
        long document = upload(Partition.PUBLIC, category, n("valid.csv"), payload("valid")).path("id").asLong();
        for (String path : List.of(VAULT, CATEGORIES, DOCUMENTS)) {
            failure(get(adminId, scoped(path, scope)), 400);
        }
        failure(post(adminId, scoped(VAULT, scope), body(Domain.VAULT_ACCOUNT, n("invalid"))), 400);
        failure(request(adminId, HttpMethod.PUT, scoped(VAULT + "/" + vault, scope),
                Map.of("name", n("invalid"), "account", n("acct"))), 400);
        failure(request(adminId, HttpMethod.DELETE, scoped(VAULT + "/" + vault, scope), null), 400);
        failure(post(adminId, scoped(VAULT + "/" + vault + "/password/reveal", scope), null), 400);
        failure(post(adminId, scoped(CATEGORIES, scope), Map.of("name", n("invalid"))), 400);
        Set<Path> before = diskPaths();
        failure(uploadRequest(adminId, scope, category, n("invalid.csv"), payload("invalid")), 400);
        assertEquals(before, diskPaths());
        failure(request(adminId, HttpMethod.DELETE, scoped(DOCUMENTS + "/" + document, scope), null), 400);
        assertEquals(400, raw(adminId, downloadPath(document, scope)).getStatusCode().value());
        assertRevealed(adminId, vault, "PUBLIC", secret);
        assertDownload(adminId, document, "PUBLIC", n("valid.csv"), payload("valid"));
    }

    @ParameterizedTest
    @CsvSource({"VAULT_ACCOUNT,PUBLIC", "VAULT_ACCOUNT,PRIVATE", "FILE_CATEGORY,PUBLIC", "FILE_CATEGORY,PRIVATE"})
    void simultaneousSamePartitionCreatesHaveOneWinnerAndDomain409s(Domain domain, String scope) throws Exception {
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            long actor = "PUBLIC".equals(scope) && i % 2 != 0 ? memberId : adminId;
            String name = " " + n(i % 2 == 0 ? "Café" : "CAFE") + " ";
            tasks.add(() -> post(actor, scoped(domain.path, scope), body(domain, name)));
        }
        int winners = 0;
        for (ResponseEntity<JsonNode> result : simultaneously(tasks)) {
            if (result.getStatusCode().value() == 200) {
                createdId(result);
                winners++;
            } else {
                conflict(result, domain);
            }
        }
        assertEquals(1, winners);
        assertEquals(1L, count("SELECT COUNT(*) FROM " + domain.table + " WHERE unique_name = ?", n("CAFE")));
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void simultaneousSameNameCreatesInThreePartitionsAllSucceed(Domain domain) throws Exception {
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (Partition partition : Partition.values()) {
            tasks.add(() -> post(actor(partition), scoped(domain.path, partition.scope), body(domain, n("parallel"))));
        }
        Set<Long> ids = new LinkedHashSet<>();
        List<ResponseEntity<JsonNode>> results = simultaneously(tasks);
        for (int i = 0; i < results.size(); i++) {
            long id = createdId(results.get(i));
            assertTrue(ids.add(id));
            assertPartition(domain.table, id, Partition.values()[i]);
        }
        assertEquals(3L, count("SELECT COUNT(*) FROM " + domain.table + " WHERE unique_name = ?", n("parallel")));
    }

    @Test
    void concurrentPrivateVaultEditsLeaveOneWinnerAndRejectedCredentialsUnchanged() throws Exception {
        List<Long> ids = new ArrayList<>();
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        String newSecret = UUID.randomUUID().toString();
        String encrypted = transport.encrypt(newSecret);
        for (int i = 0; i < CONTENDERS; i++) {
            long id = create(Domain.VAULT_ACCOUNT, adminId, "PRIVATE", n("edit" + i));
            ids.add(id);
            tasks.add(() -> request(adminId, HttpMethod.PUT, scoped(VAULT + "/" + id, "PRIVATE"),
                    Map.of("name", n("winner"), "account", n("acct"), "password", encrypted)));
        }
        List<ResponseEntity<JsonNode>> results = simultaneously(tasks);
        int winners = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getStatusCode().value() == 200) {
                ok(results.get(i));
                winners++;
                assertRevealed(adminId, ids.get(i), "PRIVATE", newSecret);
            } else {
                conflict(results.get(i), Domain.VAULT_ACCOUNT);
                assertEquals(n("edit" + i), column("vault_account", ids.get(i), "name"));
                assertRevealed(adminId, ids.get(i), "PRIVATE", secret);
            }
        }
        assertEquals(1, winners);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault_account", "file_category", "file_object"})
    void databaseDefaultsRemainPublicOwnerZeroForLegacyStyleInsert(String table) {
        long id = insert(table, sqlValues(table, "defaults"));
        assertPartition(table, id, Partition.PUBLIC);
        for (Map.Entry<String, String> expected : Map.of("scope", "PUBLIC", "owner_id", "0").entrySet()) {
            Map<String, Object> metadata = jdbc.queryForMap("SELECT COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, expected.getKey());
            assertEquals(expected.getValue(), String.valueOf(metadata.get("COLUMN_DEFAULT")));
            assertEquals("NO", metadata.get("IS_NULLABLE"));
        }
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void sqlUniqueConstraintsIncludeScopeOwnerAndNormalizedName(Domain domain) {
        for (Partition partition : Partition.values()) {
            Map<String, Object> values = sqlValues(domain.table, "sql");
            values.put("name", " " + n("Café") + " ");
            values.put("scope", partition.scope);
            values.put("owner_id", owner(partition));
            long id = insert(domain.table, values);
            assertPartition(domain.table, id, partition);
            values.put("name", n("CAFE"));
            if (domain == Domain.VAULT_ACCOUNT) {
                values.put("account", n("ACCT"));
            }
            assertThrows(DuplicateKeyException.class, () -> insert(domain.table, values));
            precheck(actor(partition), domain, partition.scope, n("CAFE"), true, null);
        }
        List<String> expectedColumns = domain == Domain.VAULT_ACCOUNT
                ? List.of("scope", "owner_id", "unique_name", "unique_account")
                : List.of("scope", "owner_id", "unique_name");
        Map<String, Set<String>> uniqueIndexes = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND NON_UNIQUE = 0", domain.table)) {
            uniqueIndexes.computeIfAbsent(row.get("INDEX_NAME").toString(), ignored -> new LinkedHashSet<>()).add(row.get("COLUMN_NAME").toString());
        }
        assertTrue(uniqueIndexes.values().stream().anyMatch(columns -> columns.equals(Set.copyOf(expectedColumns))),
                "A composite unique index must protect the complete partition key");
        Set<String> legacyColumns = domain == Domain.VAULT_ACCOUNT
                ? Set.of("unique_name", "unique_account") : Set.of("unique_name");
        assertFalse(uniqueIndexes.values().stream().anyMatch(legacyColumns::equals),
                "No global name-only unique index may remain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault_account", "file_category", "file_object"})
    void databaseChecksRejectInvalidScopeOwnerPairsOnInsertAndUpdate(String table) {
        long id = insert(table, sqlValues(table, "check-control"));
        Object[][] invalidPairs = {{"INVALID", 0L}, {"PUBLIC", adminId}, {"PRIVATE", 0L},
                {"public", 0L}, {"private", adminId}};
        for (int i = 0; i < invalidPairs.length; i++) {
            Object scope = invalidPairs[i][0];
            Object owner = invalidPairs[i][1];
            Map<String, Object> values = sqlValues(table, "bad-check" + i);
            values.put("scope", scope);
            values.put("owner_id", owner);
            assertCheckViolation(() -> insert(table, values));
            assertCheckViolation(() -> jdbc.update("UPDATE " + table + " SET scope = ?, owner_id = ? WHERE id = ?", scope, owner, id));
            assertPartition(table, id, Partition.PUBLIC);
        }
        Map<String, Object> negativeOwner = sqlValues(table, "negative");
        negativeOwner.put("scope", "PRIVATE");
        negativeOwner.put("owner_id", -1L);
        assertThrows(DataAccessException.class, () -> insert(table, negativeOwner));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE " + table + " SET scope = 'PRIVATE', owner_id = -1 WHERE id = ?", id));
        for (String column : List.of("scope", "owner_id")) {
            Map<String, Object> nullPartition = sqlValues(table, "null-" + column);
            nullPartition.put(column, null);
            assertThrows(DataIntegrityViolationException.class, () -> insert(table, nullPartition));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE " + table + " SET " + column + " = NULL WHERE id = ?", id));
        }
        assertPartition(table, id, Partition.PUBLIC);
        assertTrue(count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'", table) > 0);
    }

    @Test
    void generatedUniqueNameColumnsRemainStoredTrimmedAndAccentInsensitive() {
        for (Domain domain : Domain.values()) {
            List<String> columns = domain == Domain.VAULT_ACCOUNT ? List.of("unique_name", "unique_account") : List.of("unique_name");
            for (String column : columns) {
                Map<String, Object> definition = jdbc.queryForMap("SELECT COLLATION_NAME, GENERATION_EXPRESSION, EXTRA "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", domain.table, column);
                assertEquals("utf8mb4_0900_ai_ci", definition.get("COLLATION_NAME"));
                assertTrue(definition.get("GENERATION_EXPRESSION").toString().toLowerCase(Locale.ROOT).contains("trim("));
                assertTrue(definition.get("EXTRA").toString().contains("STORED GENERATED"));
            }
        }
    }

    private String n(String suffix) { return prefix + suffix; }
    private long actor(Partition partition) { return partition == Partition.PRIVATE_B ? memberId : adminId; }
    private long owner(Partition partition) { return partition == Partition.PUBLIC ? 0L : actor(partition); }
    private long otherActor(long actor) { return actor == adminId ? memberId : adminId; }
    private String scoped(String path, String scope) { return scope == null ? path : path + "?scope=" + scope; }
    private String downloadPath(long id, String scope) { return scoped(DOCUMENTS + "/" + id + "/download", scope); }

    private Map<String, Object> body(Domain domain, String name) {
        return domain == Domain.VAULT_ACCOUNT
                ? Map.of("name", name, "account", " " + n("Acct") + " ", "password", transportSecret)
                : Map.of("name", name);
    }

    private long create(Domain domain, long actor, String scope, String name) {
        long id = createdId(post(actor, scoped(domain.path, scope), body(domain, name)));
        assertOwnedRow(domain.table, id);
        return id;
    }

    private long category(Partition partition, String suffix) {
        return create(Domain.FILE_CATEGORY, actor(partition), partition.scope, n(suffix));
    }

    private long createdId(ResponseEntity<JsonNode> response) {
        long id = ok(response).path("data").asLong();
        assertTrue(id > 0, "Create must return its database ID");
        return id;
    }

    private Map<String, Object> precheckBody(Domain domain, String scope, String name, Long excludeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", domain.name());
        body.put("name", name);
        if (domain == Domain.VAULT_ACCOUNT) { body.put("account", n("ACCT")); }
        if (scope != null) { body.put("scope", scope); }
        if (excludeId != null) { body.put("excludeId", excludeId); }
        return body;
    }

    private void precheck(long actor, Domain domain, String scope, String name, boolean expected, Long excludeId) {
        JsonNode result = ok(post(actor, VALIDATION, precheckBody(domain, scope, name, excludeId))).path("data");
        assertTrue(result.isBoolean());
        assertEquals(expected, result.booleanValue());
    }

    private Set<Long> vaultIds(long actor, String scope) {
        String path = scoped(VAULT, scope) + (scope == null ? "?" : "&") + "keyword=" + prefix + "&pageNo=1&pageSize=100";
        JsonNode data = ok(get(actor, path)).path("data");
        assertNoPasswords(data.path("list"));
        Set<Long> ids = fixtureIds(data.path("list"));
        assertEquals(ids.size(), data.path("total").asInt());
        return ids;
    }

    private Set<Long> fixtureIds(JsonNode list) {
        assertTrue(list.isArray());
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode row : list) {
            if (row.path("name").asText().startsWith(prefix)) {
                assertTrue(ids.add(row.path("id").asLong()));
            }
        }
        return ids;
    }

    private void assertNoPasswords(JsonNode list) {
        assertTrue(list.isArray());
        for (JsonNode row : list) {
            for (String field : List.of("password", "passwordEnc", "password_enc")) {
                assertFalse(row.has(field), "List must not serialize any credential field");
            }
        }
    }

    private void assertRevealed(long actor, long id, String scope, String expected) {
        // Check ownership BEFORE the HTTP call as well as before reading the storage cipher.
        assertOwnedRow("vault_account", id);
        JsonNode data = ok(post(actor, scoped(VAULT + "/" + id + "/password/reveal", scope), null)).path("data");
        assertEquals(id, data.path("id").asLong());
        Set<String> fields = new LinkedHashSet<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "password"), fields);
        String encrypted = data.path("password").asText();
        assertFalse(encrypted.isBlank());
        assertFalse(expected.equals(encrypted), "Reveal must not send plaintext");
        // Read only the credential on an ID created by this invocation, never a legacy entry.
        String persisted = (String) column("vault_account", id, "password_enc");
        assertFalse(expected.equals(persisted), "Database must not store plaintext");
        assertFalse(persisted.equals(encrypted), "Reveal must not send the storage cipher");
        assertTrue(expected.equals(transport.decrypt(encrypted)), "Reveal transport cipher did not round-trip");
    }

    private byte[] payload(String suffix) {
        return ("fixture,value\r\n" + n(suffix) + ",\"<script>not executable</script>\"\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pngPayload() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(prefix.hashCode());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private JsonNode upload(Partition partition, long category, String name, byte[] bytes) {
        JsonNode document = ok(uploadRequest(actor(partition), partition.scope, category, name, bytes)).path("data");
        long id = document.path("id").asLong();
        assertTrue(id > 0);
        rememberFile(id);
        assertPartition("file_object", id, partition);
        assertEquals(name, document.path("name").asText());
        assertEquals(category, document.path("categoryId").asLong());
        assertEquals(bytes.length, document.path("fileSize").asInt());
        if (partition != Partition.PUBLIC) {
            assertTrue(document.path("url").isNull(), "PRIVATE upload response must have null url");
        }
        return document;
    }

    private ResponseEntity<JsonNode> uploadRequest(Long actor, String scope, long category, String name, byte[] bytes) {
        return multipart(actor, scoped(DOCUMENTS, scope), name, bytes, "application/octet-stream", Map.of("categoryId", Long.toString(category)));
    }

    private ResponseEntity<JsonNode> multipart(Long actor, String path, String name, byte[] bytes, String mime, Map<String, String> fields) {
        assertTrue(name.startsWith(prefix), "All uploads must belong to this invocation");
        uploadedPayloads.add(bytes.clone());
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(mime));
        form.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        }, partHeaders));
        fields.forEach(form::add);
        HttpHeaders headers = headers(actor);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);
    }

    private void assertDocumentUrls(JsonNode list, Partition partition) {
        for (JsonNode row : list) {
            if (row.path("name").asText().startsWith(prefix)) {
                if (partition == Partition.PUBLIC) {
                    assertEquals("/files/" + fileKey(row.path("id").asLong()), row.path("url").asText());
                } else {
                    assertTrue(row.path("url").isNull());
                }
                assertFalse(row.has("fileKey"));
                assertFalse(row.has("thumbKey"));
            }
        }
    }

    private void assertDownload(long actor, long id, String scope, String name, byte[] bytes) {
        ResponseEntity<byte[]> response = raw(actor, downloadPath(id, scope));
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(bytes, response.getBody());
        ContentDisposition disposition = response.getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals(name, disposition.getFilename());
        String cache = response.getHeaders().getCacheControl();
        assertNotNull(cache);
        assertTrue(cache.toLowerCase(Locale.ROOT).contains("no-store"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    private HttpHeaders headers(Long actor) {
        HttpHeaders headers = new HttpHeaders();
        // 身份不再是可手搓的 X-User-Id：为这个 actor 现签一枚服务端令牌，走 Authorization: Bearer。
        if (actor != null) { headers.set("Authorization", "Bearer " + sessionToken.issue(actor)); }
        return headers;
    }

    private ResponseEntity<byte[]> raw(Long actor, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(actor)), byte[].class);
    }

    private ResponseEntity<JsonNode> get(Long actor, String path) { return request(actor, HttpMethod.GET, path, null); }
    private ResponseEntity<JsonNode> post(Long actor, String path, Object body) { return request(actor, HttpMethod.POST, path, body); }

    private ResponseEntity<JsonNode> request(Long actor, HttpMethod method, String path, Object body) {
        HttpHeaders headers = headers(actor);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> response) {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("0", response.getBody().path("code").asText());
        return response.getBody();
    }

    private void failure(ResponseEntity<JsonNode> response, int status) {
        assertEquals(status, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotEquals("0", response.getBody().path("code").asText());
        assertFalse(response.getBody().hasNonNull("data"));
    }

    private void conflict(ResponseEntity<JsonNode> response, Domain domain) {
        failure(response, 409);
        assertEquals(domain.conflictCode, Objects.requireNonNull(response.getBody()).path("code").asText());
    }

    private void clientFailure(ResponseEntity<JsonNode> response) {
        assertTrue(response.getStatusCode().is4xxClientError(), "Binding documents as images must be rejected, not silently ignored");
        failure(response, response.getStatusCode().value());
    }

    private void safeUnknownFields(ResponseEntity<JsonNode> response) {
        if (response.getStatusCode().value() == 400) { failure(response, 400); } else { ok(response); }
    }

    private <T> List<T> simultaneously(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        // Java 21 close joins ALL workers before @AfterEach, including failure/timeout paths.
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            try {
                for (Callable<T> task : tasks) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(20, TimeUnit.SECONDS)) { throw new IllegalStateException("Concurrent start timed out"); }
                        return task.call();
                    }));
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS));
                start.countDown();
                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) { results.add(future.get(90, TimeUnit.SECONDS)); }
                return results;
            } finally {
                start.countDown();
            }
        }
    }

    private Map<String, Object> sqlValues(String table, String suffix) {
        Map<String, Object> values = new LinkedHashMap<>();
        if ("file_object".equals(table)) {
            values.put("file_key", n(suffix) + ".csv");
            values.put("origin_name", n(suffix) + ".csv");
            values.put("md5", UUID.randomUUID().toString().replace("-", ""));
            values.put("mime_type", "text/csv");
            values.put("ext", "csv");
            values.put("file_size", 0L);
            values.put("biz_type", "document");
            values.put("creator_id", adminId);
        } else {
            values.put("name", n(suffix));
            if ("vault_account".equals(table)) {
                values.put("account", " " + n("Acct") + " ");
                values.put("password_enc", storedSecret);
                values.put("creator_id", adminId);
            }
        }
        return values;
    }

    private long insert(String table, Map<String, Object> values) {
        List<String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        assertEquals(1, jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < columns.size(); i++) { statement.setObject(i + 1, values.get(columns.get(i))); }
            return statement;
        }, keys));
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void assertCheckViolation(Executable action) {
        // Connector/J may report CHECK errors as HY000 rather than SQLState class 23;
        // assert the exact server code instead of relying on Spring's translator subclass.
        DataAccessException error = assertThrows(DataAccessException.class, action);
        Throwable root = error.getMostSpecificCause();
        assertInstanceOf(SQLException.class, root);
        assertEquals(3819, ((SQLException) root).getErrorCode(), "Expected an enforced MySQL CHECK, not a duplicate/SQL syntax error");
    }

    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long ownFileCount() { return count("SELECT COUNT(*) FROM file_object WHERE origin_name LIKE ?", prefix + "%"); }
    private Object column(String table, long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
    }

    private void assertOwnedRow(String table, long id) {
        String nameColumn = "file_object".equals(table) ? "origin_name" : "name";
        assertEquals(1L, count("SELECT COUNT(*) FROM " + table + " WHERE id = ? AND TRIM(" + nameColumn + ") LIKE ?",
                id, prefix + "%"), "Only this invocation's rows may be accessed");
    }

    private void assertPartition(String table, long id, Partition partition) {
        assertOwnedRow(table, id);
        assertEquals(partition.scope, column(table, id, "scope"));
        assertEquals(owner(partition), ((Number) column(table, id, "owner_id")).longValue());
    }

    private String fileKey(long id) {
        assertEquals(1L, count("SELECT COUNT(*) FROM file_object WHERE id = ? AND origin_name LIKE ?", id, prefix + "%"));
        return (String) column("file_object", id, "file_key");
    }

    private void rememberFile(long id) {
        rememberKey(fileKey(id));
        rememberKey((String) column("file_object", id, "thumb_key"));
    }

    private void rememberKey(String key) {
        if (key != null && !key.isBlank()) {
            Path path = physicalPath(key);
            assertFalse(initialPaths.contains(path), "A new upload must not reuse a pre-existing path");
            ownedPaths.add(path);
            // Also clean a misplaced public-root copy ONLY for this invocation's own key.
            // It is an isolation failure, but must not leave fixture bytes behind on failure.
            Path publicCopy = publicRoot.resolve(key).normalize();
            assertTrue(publicCopy.startsWith(publicRoot));
            if (key.startsWith("private-documents/") && !initialPaths.contains(publicCopy)) {
                ownedPaths.add(publicCopy);
            }
        }
    }

    private Path physicalPath(String key) {
        assertFalse(Path.of(key).isAbsolute());
        Path root = key.startsWith("private-documents/") ? privateRoot : publicRoot;
        if (key.startsWith("private-documents/")) {
            assertTrue(key.startsWith("private-documents/" + adminId + "/")
                    || key.startsWith("private-documents/" + memberId + "/"), "Never touch another user's private storage");
        }
        // The private-documents prefix is a routing marker, not necessarily a disk folder.
        // Use the storage resolver, but independently require the correct sibling root.
        Path path = storageConfig.resolvePath(key).toAbsolutePath().normalize();
        assertTrue(path.startsWith(root));
        assertFalse(path.equals(root));
        for (Path parent = path; parent != null && parent.startsWith(root); parent = parent.getParent()) {
            assertFalse(Files.isSymbolicLink(parent), "Refusing paths through symlinks");
        }
        return path;
    }

    private Set<Path> diskPaths() throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        for (Path root : List.of(publicRoot, privateRoot)) {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                try (var stream = Files.walk(root)) {
                    stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(paths::add);
                }
            }
        }
        return paths;
    }
}
