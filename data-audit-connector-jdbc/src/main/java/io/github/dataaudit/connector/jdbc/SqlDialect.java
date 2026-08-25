// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.jdbc;

public interface SqlDialect {
    String quoteIdentifier(String identifier);

    String buildLimitedQuery(String sql, long limit);

    String buildSegmentPredicate(String column);

    String normalizeType(String typeName);
}

