package com.familyhome.boot.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;

class DuplicateExceptionHandlerTest {
    @ParameterizedTest
    @CsvSource({
            "uk_recipe_live_name,RECIPE_NAME_DUPLICATED",
            "uk_recipe_category_name,RECIPE_CATEGORY_NAME_DUPLICATED",
            "uk_practice_group_name,RECIPE_PRACTICE_GROUP_NAME_DUPLICATED",
            "uk_practice_option_name,RECIPE_PRACTICE_OPTION_NAME_DUPLICATED",
            "uk_album_group_partition_name,ALBUM_GROUP_NAME_DUPLICATED",
            "uk_user_live_name,USER_NAME_DUPLICATED",
            "uk_user_live_phone,USER_PHONE_DUPLICATED",
            "uk_vault_live_entry,VAULT_ACCOUNT_DUPLICATED",
            "uk_file_category_name,FILE_CATEGORY_NAME_DUPLICATED",
            "unknown_key,DATA_DUPLICATED"
    })
    void collisionReturnsConflictWithoutDatabaseValues(String key, String expectedCode) {
        var cause = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'private-test-value' for key 'table." + key + "'");
        var exception = new DuplicateKeyException("SQL with private-test-value", cause);
        var result = new GlobalExceptionHandler().handleDuplicate(exception,
                new MockHttpServletRequest("POST", "/api/b/test"));
        assertEquals(409, result.getStatusCode().value());
        assertEquals(expectedCode, result.getBody().getCode());
        assertFalse(result.getBody().getMessage().contains("private-test-value"));
        assertFalse(result.getBody().getMessage().contains(key));
    }

    @ParameterizedTest
    @CsvSource({"scope", "categoryId", "id"})
    void malformedParameterReturnsBadRequestWithoutSubmittedValue(String parameterName) {
        var exception = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "private-test-value", Long.class, parameterName, null,
                new IllegalArgumentException("private-test-value"));
        var result = new GlobalExceptionHandler().handleTypeMismatch(exception);
        assertEquals(400, result.getStatusCode().value());
        assertEquals("BAD_REQUEST", result.getBody().getCode());
        assertFalse(result.getBody().getMessage().contains("private-test-value"));
    }
}
