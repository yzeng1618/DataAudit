// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.jdbc;

import java.util.Locale;

abstract class AbstractSqlDialect implements SqlDialect {
    @Override
    public String buildLimitedQuery(String sql, long limit) {
        return sql + " limit " + limit;
    }

    @Override
    public String buildSegmentPredicate(String column) {
        return quoteIdentifier(column) + " = ?";
    }

    @Override
    public String normalizeType(String typeName) {
        return typeName == null ? "unknown" : typeName.toLowerCase(Locale.ROOT);
    }
}
