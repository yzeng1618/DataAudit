// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.guardrail;

import java.util.Locale;
import java.util.regex.Pattern;

public class SqlSafetyChecker {
    private static final Pattern MUTATION_KEYWORDS = Pattern.compile(
            "(?is).*\\b(insert|update|delete|merge|truncate|drop|alter|create|replace)\\b.*");

    public boolean isSafe(String sqlOrDsl) {
        if (sqlOrDsl == null || sqlOrDsl.isBlank()) {
            return true;
        }
        String normalized = sqlOrDsl.toLowerCase(Locale.ROOT);
        return !MUTATION_KEYWORDS.matcher(normalized).matches();
    }

    public void requireSafe(String sqlOrDsl) {
        if (!isSafe(sqlOrDsl)) {
            throw new IllegalArgumentException("AI generated SQL/DSL contains mutation keyword");
        }
    }
}
