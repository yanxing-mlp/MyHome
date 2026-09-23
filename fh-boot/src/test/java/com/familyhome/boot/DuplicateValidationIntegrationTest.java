package com.familyhome.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.common.auth.SessionToken;
import com.familyhome.common.crypto.TransportCipher;
import com.familyhome.user.biz.manager.UserPasswordManager;
import com.familyhome.user.biz.service.AppUserService;
import com.familyhome.vault.biz.manager.VaultCipherManager;
import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in integration tests against the dev profile's real, local MySQL 8 database.
 * No surrounding transaction: HTTP requests must see committed fixtures.
 * Each invocation owns a random prefix, including failed/concurrent writes; cleanup never
 * touches files, historical users, global counters, or another invocation's fixtures.
 * Phone fields deliberately contain non-dialable synthetic identifiers (the DTO permits them).
 * Requests/responses, passwords and stored credentials are never printed by this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.com.familyhome=INFO")
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "FH_RUN_DB_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.CONCURRENT)
class DuplicateValidationIntegrationTest {
    private static final String VALIDATION = "/api/b/validation/duplicate";
    private static final int CONTENDERS = 4;

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransportCipher transport;
    @Autowired private UserPasswordManager passwords;
    @Autowired private VaultCipherManager vaultCipher;
    @Autowired private SessionToken sessionToken;

    private final String prefix = "dv" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-";
    private boolean localDatabaseVerified;
    private long adminId;
    private long memberId;
    private String fixtureHash;
    private String transportSecret;
    private String storedSecret;

    enum Domain {
        RECIPE("recipe", "/api/b/recipe/recipes", "RECIPE_NAME_DUPLICATED"),
        RECIPE_CATEGORY("recipe_category", "/api/b/recipe/categories", "RECIPE_CATEGORY_NAME_DUPLICATED"),
        PRACTICE_GROUP("recipe_practice_group", "/api/b/recipe/practices", "RECIPE_PRACTICE_GROUP_NAME_DUPLICATED"),
        PRACTICE_OPTION("recipe_practice_option", "/api/b/recipe/practices", "RECIPE_PRACTICE_OPTION_NAME_DUPLICATED"),
        ALBUM_GROUP("album_group", "/api/b/album/groups", "ALBUM_GROUP_NAME_DUPLICATED"),
        USER_NAME("app_user", "/api/b/user", "USER_NAME_DUPLICATED"),
        USER_PHONE("app_user", "/api/b/user", "USER_PHONE_DUPLICATED"),
        VAULT_ACCOUNT("vault_account", "/api/b/vault/accounts", "VAULT_ACCOUNT_DUPLICATED"),
        FILE_CATEGORY("file_category", "/api/b/file/categories", "FILE_CATEGORY_NAME_DUPLICATED");

        final String table;
        final String path;
        final String code;

        Domain(String table, String path, String code) {
            this.table = table;
            this.path = path;
            this.code = code;
        }
    }

    @BeforeEach
    void createIsolatedActors() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata = connection.getMetaData();
            assertEquals("MySQL", metadata.getDatabaseProductName(), "A real MySQL database is required");
            assertEquals(8, metadata.getDatabaseMajorVersion(), "MySQL 8 is required");
            String url = metadata.getURL();
            assertTrue(url.startsWith("jdbc:mysql:"), "Only local MySQL JDBC connections are allowed");
            String host = URI.create(url.substring("jdbc:".length())).getHost();
            assertTrue(host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host),
                    "Refusing fixture writes outside the local database");
        }
        localDatabaseVerified = true;
        String syntheticSecret = UUID.randomUUID().toString();
        fixtureHash = passwords.hash(syntheticSecret);
        transportSecret = transport.encrypt(syntheticSecret);
        storedSecret = vaultCipher.encrypt(syntheticSecret);
        adminId = insert("app_user", Map.of("name", n("admin"), "phone", n("phone-a"),
                "password_hash", fixtureHash, "role", "ADMIN"));
        memberId = insert("app_user", Map.of("name", n("member"), "phone", n("phone-m"),
                "password_hash", fixtureHash, "role", "MEMBER"));
    }

    @AfterEach
    void removeOnlyThisInvocationsFixtures() {
        if (!localDatabaseVerified) {
            return;
        }
        // Prefix has no SQL LIKE wildcards. TRIM also finds deliberately unnormalised SQL inserts.
        // All renames keep this prefix. Options are removed by our group IDs, so even a leaked
        // temporary swap name cannot escape cleanup. Attempt every cleanup if one statement fails.
        List<String> statements = List.of(
                "DELETE FROM recipe_category_rel WHERE recipe_id IN (SELECT id FROM recipe WHERE TRIM(name) LIKE ?)",
                "DELETE FROM recipe_practice_rel WHERE recipe_id IN (SELECT id FROM recipe WHERE TRIM(name) LIKE ?)",
                "DELETE FROM recipe_practice_option WHERE group_id IN (SELECT id FROM recipe_practice_group WHERE TRIM(name) LIKE ?)",
                "DELETE FROM recipe WHERE TRIM(name) LIKE ?",
                "DELETE FROM recipe_practice_group WHERE TRIM(name) LIKE ?",
                "DELETE FROM recipe_category WHERE TRIM(name) LIKE ?",
                "DELETE FROM album_group WHERE TRIM(name) LIKE ?",
                "DELETE FROM vault_account WHERE TRIM(name) LIKE ?",
                "DELETE FROM file_category WHERE TRIM(name) LIKE ?",
                "DELETE FROM app_user WHERE TRIM(name) LIKE ?");
        assertAll("Clean up this invocation only", statements.stream()
                .map(sql -> (org.junit.jupiter.api.function.Executable) () -> jdbc.update(sql, prefix + "%")));
    }

    @Test
    void generatedColumnsUseMysqlTrimAndAccentInsensitiveCollation() {
        Map<String, List<String>> columns = Map.of(
                "recipe", List.of("unique_name"),
                "recipe_category", List.of("unique_name"),
                "recipe_practice_group", List.of("unique_name"),
                "recipe_practice_option", List.of("unique_name"),
                "album_group", List.of("unique_name"),
                "file_category", List.of("unique_name"),
                "app_user", List.of("unique_name", "unique_phone"),
                "vault_account", List.of("unique_name", "unique_account"));
        columns.forEach((table, names) -> names.forEach(column -> {
            Map<String, Object> definition = jdbc.queryForMap(
                    "SELECT COLLATION_NAME, CHARACTER_SET_NAME, GENERATION_EXPRESSION, EXTRA "
                            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, column);
            assertEquals("utf8mb4_0900_ai_ci", definition.get("COLLATION_NAME"), table + "." + column);
            assertEquals("utf8mb4", definition.get("CHARACTER_SET_NAME"));
            assertTrue(definition.get("GENERATION_EXPRESSION").toString().toLowerCase(Locale.ROOT).contains("trim("));
            assertTrue(definition.get("EXTRA").toString().contains("STORED GENERATED"));
        }));
        String ownerExpression = jdbc.queryForObject(
                "SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'album_group' AND COLUMN_NAME = 'unique_owner'",
                String.class);
        assertNotNull(ownerExpression);
        assertTrue(ownerExpression.toLowerCase(Locale.ROOT).contains("creator_id"));
        assertTrue(ownerExpression.toUpperCase(Locale.ROOT).contains("PERSONAL"));
    }

    @Test
    void recipeCreateAndUpdateTrimAndShareCaseAccentUniquenessAcrossUsers() {
        long category = category();
        precheck(adminId, Domain.RECIPE, n("Café"), false);
        long first = create(adminId, Domain.RECIPE, recipeBody("  " + n("Café") + "  ", category));
        assertColumn("recipe", first, "name", n("Café"));
        precheck(memberId, Domain.RECIPE, " " + n("CAFE") + " ", true);
        precheck(adminId, Domain.RECIPE, n("CAFE"), false, "excludeId", first);
        conflict(post(memberId, Domain.RECIPE.path, recipeBody(n("CAFE"), category)), Domain.RECIPE);
        ok(put(adminId, Domain.RECIPE.path + "/" + first, Map.of("name", " " + n("CAFÉ") + " ")));
        assertColumn("recipe", first, "name", n("CAFÉ"));
        long second = create(memberId, Domain.RECIPE, recipeBody(n("second"), category));
        conflict(put(memberId, Domain.RECIPE.path + "/" + second,
                Map.of("name", " " + n("cafe") + " ", "description", "must roll back")), Domain.RECIPE);
        assertColumn("recipe", second, "name", n("second"));
        assertNull(column("recipe", second, "description"));
        ok(put(memberId, Domain.RECIPE.path + "/" + second, Map.of("name", " " + n("edited") + " ")));
        assertColumn("recipe", second, "name", n("edited"));
    }

    @Test
    void offShelfRecipeOccupiesNameButRepeatedSoftDeletionReleasesIt() {
        long category = category();
        long id = create(adminId, Domain.RECIPE, recipeBody(n("Café"), category));
        ok(put(adminId, Domain.RECIPE.path + "/" + id,
                Map.of("name", n("Café"), "status", "OFF_SHELF")));
        assertColumn("recipe", id, "status", "OFF_SHELF");
        precheck(memberId, Domain.RECIPE, n("CAFE"), true);
        conflict(post(memberId, Domain.RECIPE.path, recipeBody(n("CAFE"), category)), Domain.RECIPE);
        for (int cycle = 0; cycle < 2; cycle++) {
            ok(delete(adminId, Domain.RECIPE.path + "/" + id));
            assertColumn("recipe", id, "status", "DELETED");
            assertNull(column("recipe", id, "unique_name"));
            precheck(adminId, Domain.RECIPE, n("CAFE"), false);
            long replacement = create(memberId, Domain.RECIPE, recipeBody(n("CAFE"), category));
            assertNotEquals(id, replacement);
            id = replacement;
        }
    }

    @ParameterizedTest(name = "dictionary normalization and conflict: {0}")
    @EnumSource(value = Domain.class, names = {"RECIPE_CATEGORY", "PRACTICE_GROUP", "FILE_CATEGORY"})
    void dictionariesEnforceTrimmedNamesAndExcludeSelf(Domain domain) {
        precheck(adminId, domain, n("Café"), false);
        long first = create(adminId, domain, Map.of("name", " " + n("Café") + " "));
        assertColumn(domain.table, first, "name", n("Café"));
        precheck(adminId, domain, n("CAFE"), true);
        precheck(adminId, domain, n("CAFE"), false, "excludeId", first);
        conflict(post(adminId, domain.path, Map.of("name", " " + n("CAFE") + " ")), domain);
        if (domain != Domain.FILE_CATEGORY) { // File categories have no update/delete controller routes.
            long second = create(adminId, domain, Map.of("name", n("second")));
            conflict(put(adminId, domain.path + "/" + second, Map.of("name", n("cafe"))), domain);
            assertColumn(domain.table, second, "name", n("second"));
            ok(put(adminId, domain.path + "/" + first, Map.of("name", " " + n("CAFÉ") + " ")));
            assertColumn(domain.table, first, "name", n("CAFÉ"));
            ok(delete(adminId, domain.path + "/" + first));
            precheck(adminId, domain, n("CAFE"), false);
            assertNotEquals(first, create(adminId, domain, Map.of("name", n("CAFE"))));
        }
    }

    @Test
    void familyAlbumNamesAreSharedButPersonalNamesBelongToEachUser() {
        long family = album(adminId, " " + n("Café") + " ", "FAMILY");
        assertColumn("album_group", family, "name", n("Café"));
        precheck(memberId, Domain.ALBUM_GROUP, n("CAFE"), true); // Omitted scope defaults to FAMILY.
        conflict(post(memberId, Domain.ALBUM_GROUP.path,
                Map.of("name", n("CAFE"), "scope", "FAMILY")), Domain.ALBUM_GROUP);
        precheck(adminId, Domain.ALBUM_GROUP, n("CAFE"), false, "scope", "PERSONAL");
        long personalA = album(adminId, n("Café"), "PERSONAL");
        precheck(adminId, Domain.ALBUM_GROUP, n("CAFE"), true, "scope", "PERSONAL");
        precheck(memberId, Domain.ALBUM_GROUP, n("CAFE"), false, "scope", "PERSONAL");
        long personalB = album(memberId, n("CAFE"), "PERSONAL");
        conflict(post(memberId, Domain.ALBUM_GROUP.path,
                Map.of("name", " " + n("café") + " ", "scope", "PERSONAL")), Domain.ALBUM_GROUP);
        assertEquals(0L, ((Number) column("album_group", family, "unique_owner")).longValue());
        assertEquals(adminId, ((Number) column("album_group", personalA, "unique_owner")).longValue());
        assertEquals(memberId, ((Number) column("album_group", personalB, "unique_owner")).longValue());
    }

    @ParameterizedTest(name = "album edit, off shelf and deletion: {0}")
    @ValueSource(strings = {"FAMILY", "PERSONAL"})
    void albumEditsAndSoftDeletionRespectPartitionAndKeepTombstonesReusable(String scope) {
        long id = album(adminId, n("Café"), scope);
        long second = album(adminId, n("second"), scope);
        String secondPath = albumPath(second, scope);
        conflict(put(adminId, secondPath, Map.of("name", n("CAFE"), "status", "OFF_SHELF")), Domain.ALBUM_GROUP);
        assertColumn("album_group", second, "name", n("second"));
        assertColumn("album_group", second, "status", "ON_SHELF");
        precheck(adminId, Domain.ALBUM_GROUP, n("CAFE"), false, "scope", scope, "excludeId", id);
        ok(put(adminId, albumPath(id, scope), Map.of("name", " " + n("CAFÉ") + " ", "status", "OFF_SHELF")));
        assertColumn("album_group", id, "name", n("CAFÉ"));
        assertColumn("album_group", id, "status", "OFF_SHELF");
        precheck(adminId, Domain.ALBUM_GROUP, n("CAFE"), true, "scope", scope);
        conflict(post(adminId, Domain.ALBUM_GROUP.path, Map.of("name", n("CAFE"), "scope", scope)), Domain.ALBUM_GROUP);
        for (int cycle = 0; cycle < 2; cycle++) {
            // Only delete our empty groups: never let this test reach file purge/city recalculation.
            assertEquals(0L, count("SELECT COUNT(*) FROM album_image_group_rel WHERE group_id = ?", id));
            ok(delete(adminId, albumPath(id, scope)));
            assertColumn("album_group", id, "status", "DELETED");
            assertNull(column("album_group", id, "unique_name"));
            precheck(adminId, Domain.ALBUM_GROUP, n("CAFE"), false, "scope", scope);
            long replacement = album(adminId, n("CAFE"), scope);
            assertNotEquals(id, replacement);
            id = replacement;
        }
    }

    @Test
    void userCreationChecksLiveNameAndPhoneIndependentlyAndTrimsBoth() {
        precheck(adminId, Domain.USER_NAME, n("Café"), false);
        precheck(adminId, Domain.USER_PHONE, n("ph-one"), false);
        long id = user(" " + n("Café") + " ", " " + n("ph-one") + " ");
        assertColumn("app_user", id, "name", n("Café"));
        assertColumn("app_user", id, "phone", n("ph-one"));
        precheck(adminId, Domain.USER_NAME, n("CAFE"), true);
        precheck(adminId, Domain.USER_PHONE, " " + n("ph-one") + " ", true);
        precheck(id, Domain.USER_NAME, n("CAFE"), false, "excludeId", id);
        precheck(id, Domain.USER_PHONE, n("ph-one"), false, "excludeId", id);
        conflict(post(adminId, Domain.USER_NAME.path, Map.of("name", n("CAFE"), "phone", n("ph-two"))), Domain.USER_NAME);
        conflict(post(adminId, Domain.USER_NAME.path, Map.of("name", n("other"), "phone", " " + n("ph-one") + " ")), Domain.USER_PHONE);
        assertEquals(1L, count("SELECT COUNT(*) FROM app_user WHERE unique_name = ?", n("CAFE")));
    }

    @Test
    void profileConflictsRollBackAndDeletedUsersReleaseBothKeysRepeatedly() {
        long first = user(n("Café"), n("ph-one"));
        long second = user(n("second"), n("ph-two"));
        ok(put(first, "/api/b/user/profile", Map.of("name", " " + n("CAFÉ") + " ", "phone", " " + n("ph-one") + " ")));
        assertColumn("app_user", first, "name", n("CAFÉ"));
        assertColumn("app_user", first, "phone", n("ph-one"));
        conflict(put(second, "/api/b/user/profile", Map.of("name", n("CAFE"), "phone", n("changed"))), Domain.USER_NAME);
        conflict(put(second, "/api/b/user/profile", Map.of("name", n("changed"), "phone", n("ph-one"))), Domain.USER_PHONE);
        assertColumn("app_user", second, "name", n("second"));
        assertColumn("app_user", second, "phone", n("ph-two"));
        ok(put(second, "/api/b/user/profile", Map.of("name", " " + n("edited") + " ", "phone", " " + n("edited-p") + " ")));
        assertColumn("app_user", second, "name", n("edited"));
        assertColumn("app_user", second, "phone", n("edited-p"));
        for (int cycle = 0; cycle < 2; cycle++) {
            ok(delete(adminId, "/api/b/user/" + first));
            assertNull(column("app_user", first, "unique_name"));
            assertNull(column("app_user", first, "unique_phone"));
            precheck(adminId, Domain.USER_NAME, n("CAFE"), false);
            precheck(adminId, Domain.USER_PHONE, n("ph-one"), false);
            long replacement = user(n("CAFE"), n("ph-one"));
            assertNotEquals(first, replacement);
            first = replacement;
        }
    }

    @Test
    void twoUsersMaySetAndLoginWithTheSamePassword() {
        long first = user(n("u-one"), n("ph-one"));
        long second = user(n("u-two"), n("ph-two"));
        String sharedPassword = UUID.randomUUID().toString();
        for (long id : List.of(first, second)) {
            ok(put(id, "/api/b/user/profile/password", Map.of(
                    "oldPassword", transport.encrypt(AppUserService.DEFAULT_INITIAL_PASSWORD),
                    "newPassword", transport.encrypt(sharedPassword))));
            JsonNode login = ok(post(null, "/api/b/user/login",
                    Map.of("userId", id, "password", transport.encrypt(sharedPassword))));
            assertEquals(id, login.path("data").path("id").asLong());
            assertFalse(login.path("data").has("password"));
            assertFalse(login.path("data").has("passwordHash"));
        }
    }

    @Test
    void vaultUniquenessIsPlatformAndAccountNotPasswordOrCreator() {
        precheck(adminId, Domain.VAULT_ACCOUNT, n("Café"), false, "account", n("Accént"));
        long first = create(adminId, Domain.VAULT_ACCOUNT, vaultBody(" " + n("Café") + " ", " " + n("Accént") + " "));
        assertColumn("vault_account", first, "name", n("Café"));
        assertColumn("vault_account", first, "account", n("Accént"));
        precheck(memberId, Domain.VAULT_ACCOUNT, n("CAFE"), true, "account", " " + n("ACCENT") + " ");
        precheck(memberId, Domain.VAULT_ACCOUNT, n("CAFE"), false, "account", n("ACCENT"), "excludeId", first);
        conflict(post(memberId, Domain.VAULT_ACCOUNT.path,
                Map.of("name", n("CAFE"), "account", n("ACCENT"), "password", transport.encrypt(UUID.randomUUID().toString()))),
                Domain.VAULT_ACCOUNT);
        long otherAccount = create(memberId, Domain.VAULT_ACCOUNT, vaultBody(n("Café"), n("other")));
        long otherPlatform = create(memberId, Domain.VAULT_ACCOUNT, vaultBody(n("other"), n("Accént")));
        assertNotEquals(first, otherAccount);
        assertNotEquals(first, otherPlatform);
        assertNotEquals(otherAccount, otherPlatform);
        precheck(adminId, Domain.VAULT_ACCOUNT, n("Café"), false, "account", n("unused"));
    }

    @Test
    void vaultEditsRollBackCredentialsAndDeletionReleasesThePair() {
        long first = create(adminId, Domain.VAULT_ACCOUNT, vaultBody(n("Café"), n("Acct")));
        long second = create(adminId, Domain.VAULT_ACCOUNT, vaultBody(n("second"), n("other")));
        Object originalSecret = column("vault_account", second, "password_enc");
        conflict(put(adminId, Domain.VAULT_ACCOUNT.path + "/" + second,
                Map.of("name", n("CAFE"), "account", n("ACCT"), "password", transport.encrypt(UUID.randomUUID().toString()))),
                Domain.VAULT_ACCOUNT);
        assertColumn("vault_account", second, "name", n("second"));
        assertColumn("vault_account", second, "account", n("other"));
        assertTrue(Objects.equals(originalSecret, column("vault_account", second, "password_enc")), "Failed edit changed credentials");
        ok(put(adminId, Domain.VAULT_ACCOUNT.path + "/" + second,
                Map.of("name", " " + n("edited") + " ", "account", " " + n("edited-a") + " ")));
        assertColumn("vault_account", second, "name", n("edited"));
        assertColumn("vault_account", second, "account", n("edited-a"));
        assertTrue(Objects.equals(originalSecret, column("vault_account", second, "password_enc")), "Omitted password must remain unchanged");
        ok(put(adminId, Domain.VAULT_ACCOUNT.path + "/" + first,
                Map.of("name", " " + n("CAFÉ") + " ", "account", " " + n("ACCT") + " ")));
        for (int cycle = 0; cycle < 2; cycle++) {
            ok(delete(adminId, Domain.VAULT_ACCOUNT.path + "/" + first));
            assertNull(column("vault_account", first, "unique_name"));
            assertNull(column("vault_account", first, "unique_account"));
            precheck(adminId, Domain.VAULT_ACCOUNT, n("CAFE"), false, "account", n("ACCT"));
            long replacement = create(memberId, Domain.VAULT_ACCOUNT, vaultBody(n("CAFE"), n("ACCT")));
            assertNotEquals(first, replacement);
            first = replacement;
        }
    }

    @Test
    void practiceOptionsAreUniqueWithinGroupAndDuplicateBatchRollsBack() {
        long first = group("g-one");
        long second = group("g-two");
        ok(put(adminId, Domain.PRACTICE_GROUP.path + "/" + first,
                Map.of("name", n("g-one"), "options", List.of(Map.of("name", " " + n("Café") + " "), Map.of("name", n("Tea"))))));
        Map<Long, String> before = options(first);
        assertEquals(2, before.size());
        assertTrue(before.containsValue(n("Café")));
        long cafeId = optionId(before, n("Café"));
        precheck(adminId, Domain.PRACTICE_OPTION, n("CAFE"), true, "groupId", first);
        precheck(adminId, Domain.PRACTICE_OPTION, n("CAFE"), false, "groupId", first, "excludeId", cafeId);
        precheck(adminId, Domain.PRACTICE_OPTION, n("CAFE"), false, "groupId", second);
        ok(put(adminId, Domain.PRACTICE_GROUP.path + "/" + second,
                Map.of("name", n("g-two"), "options", List.of(Map.of("name", n("CAFE"))))));
        assertEquals(1, options(second).size());
        conflict(put(adminId, Domain.PRACTICE_GROUP.path + "/" + first,
                Map.of("name", n("changed"), "options", List.of(Map.of("name", n("Café")), Map.of("name", " " + n("CAFE") + " ")))),
                Domain.PRACTICE_OPTION);
        assertColumn("recipe_practice_group", first, "name", n("g-one"));
        assertEquals(before, options(first), "Deleted/reinserted/temporarily renamed options must all roll back");
        conflict(put(adminId, Domain.PRACTICE_GROUP.path + "/" + first,
                Map.of("name", n("changed"), "options", List.of(
                        Map.of("id", cafeId, "name", n("Café")),
                        Map.of("id", optionId(before, n("Tea")), "name", " " + n("CAFE") + " ")))), Domain.PRACTICE_OPTION);
        assertColumn("recipe_practice_group", first, "name", n("g-one"));
        assertEquals(before, options(first), "Conflicting edits of retained IDs must roll back too");
    }

    @Test
    void practiceOptionNameSwapPreservesIdsAndTrimsEditedNames() {
        long group = group("g-one");
        Map<Long, String> before = twoOptions(group, "g-one");
        long a = optionId(before, n("Café"));
        long b = optionId(before, n("Tea"));
        ok(put(adminId, Domain.PRACTICE_GROUP.path + "/" + group,
                Map.of("name", " " + n("g-one") + " ", "options", List.of(
                        Map.of("id", a, "name", " " + n("Tea") + " "),
                        Map.of("id", b, "name", " " + n("Café") + " ")))));
        assertEquals(Map.of(a, n("Tea"), b, n("Café")), options(group));
        assertColumn("recipe_practice_group", group, "name", n("g-one"));
        precheck(adminId, Domain.PRACTICE_OPTION, n("CAFE"), false, "groupId", group, "excludeId", b);
    }

    @ParameterizedTest(name = "invalid option IDs roll back: {0}")
    @ValueSource(strings = {"DUPLICATE_ID", "FOREIGN_ID"})
    void invalidOptionIdsRejectEntireGroupEdit(String scenario) {
        long first = group("g-one");
        long second = group("g-two");
        Map<Long, String> before = twoOptions(first, "g-one");
        Map<Long, String> foreignBefore = twoOptions(second, "g-two");
        long ownId = optionId(before, n("Café"));
        long badId = "DUPLICATE_ID".equals(scenario) ? ownId : optionId(foreignBefore, n("Tea"));
        ResponseEntity<JsonNode> response = put(adminId, Domain.PRACTICE_GROUP.path + "/" + first,
                Map.of("name", n("changed"), "options", List.of(
                        Map.of("id", ownId, "name", n("new-a")), Map.of("id", badId, "name", n("new-b")))));
        if ("DUPLICATE_ID".equals(scenario)) {
            failure(response, 400, "BAD_REQUEST");
        } else {
            // The existing service uses BizException.notFound(null, ...); do not invent a 404 code.
            assertTrue(response.getStatusCode().is4xxClientError());
            assertNotNull(response.getBody());
            assertNotEquals("0", response.getBody().path("code").asText());
        }
        assertColumn("recipe_practice_group", first, "name", n("g-one"));
        assertEquals(before, options(first));
        assertEquals(foreignBefore, options(second));
    }

    @Test
    void duplicatePrecheckRejectsInvalidPayloadsAndUnauthorizedUserChecks() {
        failure(post(adminId, VALIDATION, Map.of("kind", "RECIPE", "name", "   ")), 400, "BAD_REQUEST");
        failure(post(adminId, VALIDATION, Map.of("kind", "UNKNOWN", "name", n("x"))), 400, "BAD_REQUEST");
        failure(post(adminId, VALIDATION, Map.of("name", n("x"))), 400, "BAD_REQUEST");
        failure(post(adminId, VALIDATION, Map.of("kind", "PRACTICE_OPTION", "name", n("x"))), 400, "BAD_REQUEST");
        failure(post(adminId, VALIDATION, Map.of("kind", "VAULT_ACCOUNT", "name", n("x"))), 400, "BAD_REQUEST");
        failure(post(adminId, VALIDATION, Map.of("kind", "RECIPE", "name", n("x"), "excludeId", 0)), 400, "BAD_REQUEST");
        failure(post(null, VALIDATION, Map.of("kind", "RECIPE", "name", n("x"))), 401, "USER_NOT_LOGIN");
        for (String kind : List.of("USER_NAME", "USER_PHONE")) {
            failure(post(memberId, VALIDATION, Map.of("kind", kind, "name", n("x"))), 403, "USER_FORBIDDEN");
            failure(post(adminId, VALIDATION, Map.of("kind", kind, "name", n("x"), "excludeId", memberId)), 403, "USER_FORBIDDEN");
        }
    }

    @ParameterizedTest(name = "HTTP concurrent create: {0}")
    @EnumSource(value = Domain.class, mode = EnumSource.Mode.EXCLUDE, names = "PRACTICE_OPTION")
    void concurrentHttpCreatesHaveExactlyOneWinnerAndDomainSpecific409s(Domain domain) throws Exception {
        long category = domain == Domain.RECIPE ? category() : 0;
        List<Callable<ResponseEntity<JsonNode>>> requests = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            final int attempt = i;
            Map<String, Object> body = concurrentBody(domain, variant(i), attempt, category);
            long actor = domain == Domain.USER_NAME || domain == Domain.USER_PHONE || i % 2 == 0 ? adminId : memberId;
            requests.add(() -> post(actor, domain.path, body));
        }
        List<ResponseEntity<JsonNode>> results = simultaneously(requests);
        int winners = 0;
        for (ResponseEntity<JsonNode> result : results) {
            if (result.getStatusCode().value() == 200) {
                assertTrue(ok(result).path("data").asLong() > 0);
                winners++;
            } else {
                conflict(result, domain);
            }
        }
        assertEquals(1, winners, domain + " must have exactly one committed HTTP create");
        assertEquals(1L, liveMatches(domain));
    }

    @ParameterizedTest(name = "HTTP concurrent edit: {0}")
    @EnumSource(value = Domain.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"PRACTICE_OPTION", "FILE_CATEGORY"})
    void concurrentEditsHaveOneWinnerAndPreserveRejectedRows(Domain domain) throws Exception {
        long category = domain == Domain.RECIPE ? category() : 0;
        List<Long> ids = new ArrayList<>();
        List<Callable<ResponseEntity<JsonNode>>> requests = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            String original = n("edit-" + i);
            long id = create(adminId, domain, concurrentBody(domain, original, i, category));
            ids.add(id);
            Map<String, Object> patch = concurrentBody(domain, variant(i), i, category);
            if (domain == Domain.USER_NAME || domain == Domain.USER_PHONE) {
                requests.add(() -> put(id, "/api/b/user/profile", patch));
            } else {
                requests.add(() -> put(adminId, domain.path + "/" + id, patch));
            }
        }
        List<ResponseEntity<JsonNode>> results = simultaneously(requests);
        int winners = 0;
        for (int i = 0; i < results.size(); i++) {
            ResponseEntity<JsonNode> result = results.get(i);
            if (result.getStatusCode().value() == 200) {
                ok(result);
                winners++;
            } else {
                conflict(result, domain);
                assertColumn(domain.table, ids.get(i), domain == Domain.USER_PHONE ? "phone" : "name", n("edit-" + i));
            }
        }
        assertEquals(1, winners, domain + " must have one successful edit");
        assertEquals(1L, liveMatches(domain));
    }

    @Test
    void concurrentRecipeEditCannotResurrectDeletedRecipeOrReoccupyName() throws Exception {
        long category = category();
        for (int round = 0; round < 8; round++) {
            String name = n("delete-race-" + round);
            long id = create(adminId, Domain.RECIPE, recipeBody(name, category));
            List<ResponseEntity<JsonNode>> results = simultaneously(List.of(
                    () -> put(adminId, Domain.RECIPE.path + "/" + id, Map.of("name", name)),
                    () -> delete(memberId, Domain.RECIPE.path + "/" + id)));
            ok(results.get(1));
            ResponseEntity<JsonNode> edit = results.get(0);
            assertTrue(edit.getStatusCode().value() == 200 || edit.getStatusCode().is4xxClientError());
            assertColumn("recipe", id, "status", "DELETED");
            assertNull(column("recipe", id, "unique_name"));
            precheck(adminId, Domain.RECIPE, name, false);
            assertNotEquals(id, create(memberId, Domain.RECIPE, recipeBody(name, category)));
        }
    }

    @Test
    void removedOptionCanBeReplacedWithSameNameWithoutChangingRetainedIds() {
        long group = group("g-one");
        Map<Long, String> before = twoOptions(group, "g-one");
        long retained = optionId(before, n("Tea"));
        long removed = optionId(before, n("Café"));
        ok(put(adminId, Domain.PRACTICE_GROUP.path + "/" + group,
                Map.of("name", n("g-one"), "options", List.of(
                        Map.of("id", retained, "name", n("Tea")), Map.of("name", " " + n("CAFE") + " ")))));
        Map<Long, String> after = options(group);
        assertEquals(2, after.size());
        assertEquals(n("Tea"), after.get(retained));
        assertFalse(after.containsKey(removed));
        assertTrue(after.containsValue(n("CAFE")));
    }

    @ParameterizedTest(name = "direct MySQL concurrent unique constraint: {0}")
    @EnumSource(Domain.class)
    void directSqlCannotBypassAnyUniqueConstraintEvenUnderConcurrency(Domain domain) throws Exception {
        long group = domain == Domain.PRACTICE_OPTION ? group("sqlgroup") : 0;
        List<Callable<Boolean>> inserts = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            Map<String, Object> values = sqlValues(domain, variant(i), i, group);
            inserts.add(() -> {
                try {
                    insert(domain.table, values); // No controller, service precheck, or Java normalization.
                    return true;
                } catch (DuplicateKeyException expected) {
                    return false; // Other SQL/connection errors must fail, never count as a duplicate.
                }
            });
        }
        List<Boolean> results = simultaneously(inserts);
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count(), domain + " database unique index missing");
        assertEquals(1L, liveMatches(domain));
        String key = domain == Domain.USER_PHONE ? "unique_phone" : "unique_name";
        String source = domain == Domain.USER_PHONE ? "phone" : "name";
        Map<String, Object> winner = jdbc.queryForMap("SELECT " + source + ", " + key + " FROM " + domain.table
                + " WHERE " + key + " = ?", n("CAFE"));
        assertEquals(winner.get(source).toString().trim(), winner.get(key));
        Object[] extra = switch (domain) {
            case PRACTICE_OPTION -> new Object[]{"groupId", group};
            case VAULT_ACCOUNT -> new Object[]{"account", n("ACCT")};
            default -> new Object[0];
        };
        // Raw/legacy leading spaces must not make precheck and write endpoints disagree.
        precheck(adminId, domain, n("CAFE"), true, extra);
        if (domain != Domain.PRACTICE_OPTION) {
            long category = domain == Domain.RECIPE ? category() : 0;
            conflict(post(adminId, domain.path, concurrentBody(domain, n("CAFE"), 0, category)), domain);
            assertEquals(1L, liveMatches(domain));
        }
    }

    private Map<String, Object> concurrentBody(Domain domain, String name, int attempt, long category) {
        return switch (domain) {
            case RECIPE -> recipeBody(name, category);
            case ALBUM_GROUP -> Map.of("name", name, "scope", "FAMILY");
            case USER_NAME -> Map.of("name", name, "phone", n("race-p" + attempt));
            case USER_PHONE -> Map.of("name", n("race-u" + attempt), "phone", name);
            case VAULT_ACCOUNT -> vaultBody(name, " " + n("Acct") + " ");
            default -> Map.of("name", name);
        };
    }

    private Map<String, Object> sqlValues(Domain domain, String name, int attempt, long group) {
        return switch (domain) {
            case RECIPE -> Map.of("name", name, "status", "ON_SHELF", "creator_id", adminId);
            case ALBUM_GROUP -> Map.of("name", name, "scope", "FAMILY", "status", "ON_SHELF",
                    "creator_id", attempt % 2 == 0 ? adminId : memberId);
            case PRACTICE_OPTION -> Map.of("name", name, "group_id", group);
            case USER_NAME -> Map.of("name", name, "phone", n("sql-p" + attempt), "password_hash", fixtureHash);
            case USER_PHONE -> Map.of("name", n("sql-u" + attempt), "phone", name, "password_hash", fixtureHash);
            case VAULT_ACCOUNT -> Map.of("name", name, "account", " " + n(attempt % 2 == 0 ? "Acct" : "ACCT") + " ",
                    "password_enc", storedSecret, "creator_id", adminId);
            default -> Map.of("name", name);
        };
    }

    private <T> List<T> simultaneously(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        // Java 21 close waits for ALL workers, including on assertion/future failure, before @AfterEach.
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            try {
                for (Callable<T> task : tasks) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent start barrier timed out");
                        }
                        return task.call();
                    }));
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS), "All contenders must reach the barrier");
                start.countDown();
                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(90, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                start.countDown();
            }
        }
    }

    private long liveMatches(Domain domain) {
        String key = domain == Domain.USER_PHONE ? "unique_phone" : "unique_name";
        return count("SELECT COUNT(*) FROM " + domain.table + " WHERE " + key + " = ?", n("CAFE"));
    }

    private String n(String suffix) {
        return prefix + suffix;
    }

    private String variant(int attempt) {
        return " " + (attempt % 2 == 0 ? n("Café") : n("CAFE").toUpperCase(Locale.ROOT)) + " ";
    }

    private long category() {
        return create(adminId, Domain.RECIPE_CATEGORY, Map.of("name", n("category")));
    }

    private long group(String suffix) {
        return create(adminId, Domain.PRACTICE_GROUP, Map.of("name", n(suffix)));
    }

    private long user(String name, String phone) {
        return create(adminId, Domain.USER_NAME, Map.of("name", name, "phone", phone));
    }

    private long album(long actor, String name, String scope) {
        return create(actor, Domain.ALBUM_GROUP, Map.of("name", name, "scope", scope));
    }

    private String albumPath(long id, String scope) {
        return Domain.ALBUM_GROUP.path + "/" + id + "?scope=" + scope;
    }

    private Map<String, Object> recipeBody(String name, long category) {
        return Map.of("name", name, "categoryId", category, "status", "ON_SHELF");
    }

    private Map<String, Object> vaultBody(String name, String account) {
        return Map.of("name", name, "account", account, "password", transportSecret);
    }

    private Map<Long, String> twoOptions(long group, String suffix) {
        ok(put(adminId, Domain.PRACTICE_GROUP.path + "/" + group,
                Map.of("name", n(suffix), "options", List.of(Map.of("name", n("Café")), Map.of("name", n("Tea"))))));
        Map<Long, String> result = options(group);
        assertEquals(2, result.size());
        return result;
    }

    private Map<Long, String> options(long group) {
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM recipe_practice_option WHERE group_id = ? ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(row.getLong("id"), row.getString("name")), group);
        return result;
    }

    private long optionId(Map<Long, String> options, String name) {
        return options.entrySet().stream().filter(entry -> name.equals(entry.getValue()))
                .mapToLong(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private void precheck(long actor, Domain domain, String name, boolean expected, Object... extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", domain.name());
        body.put("name", name);
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        JsonNode data = ok(post(actor, VALIDATION, body)).path("data");
        assertTrue(data.isBoolean(), "Precheck must return Result<Boolean>, not an entity or page");
        assertEquals(expected, data.booleanValue(), domain + " precheck (true means duplicate)");
    }

    private long create(long actor, Domain domain, Map<String, Object> body) {
        long id = ok(post(actor, domain.path, body)).path("data").asLong();
        assertTrue(id > 0, "Create must return its database ID");
        return id;
    }

    private ResponseEntity<JsonNode> post(Long actor, String path, Map<String, Object> body) {
        return request(actor, HttpMethod.POST, path, body);
    }

    private ResponseEntity<JsonNode> put(long actor, String path, Map<String, Object> body) {
        return request(actor, HttpMethod.PUT, path, body);
    }

    private ResponseEntity<JsonNode> delete(long actor, String path) {
        return request(actor, HttpMethod.DELETE, path, null);
    }

    private ResponseEntity<JsonNode> request(Long actor, HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            // 身份不再是可手搓的 X-User-Id：为这个 actor 现签一枚服务端令牌，走 Authorization: Bearer。
            headers.set("Authorization", "Bearer " + sessionToken.issue(actor));
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> response) {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("0", response.getBody().path("code").asText());
        return response.getBody();
    }

    private void conflict(ResponseEntity<JsonNode> response, Domain domain) {
        failure(response, 409, domain.code);
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().path("code").asText());
        assertFalse(response.getBody().hasNonNull("data"));
    }

    private long insert(String table, Map<String, Object> values) {
        // SQL identifiers below come only from constants in this test; all data is bound.
        List<String> columns = new ArrayList<>(values.keySet());
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")";
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int rows = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < columns.size(); i++) {
                statement.setObject(i + 1, values.get(columns.get(i)));
            }
            return statement;
        }, keys);
        assertEquals(1, rows);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private long count(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private Object column(String table, long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
    }

    private void assertColumn(String table, long id, String column, Object expected) {
        assertEquals(expected, column(table, id, column), table + "." + column);
    }
}
