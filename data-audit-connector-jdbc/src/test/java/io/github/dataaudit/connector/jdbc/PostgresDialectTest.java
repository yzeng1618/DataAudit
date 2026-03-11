package io.github.dataaudit.connector.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresDialectTest {
    @Test
    void shouldBuildLimitedQuery() {
        PostgresDialect dialect = new PostgresDialect();
        assertEquals("select * from t limit 10", dialect.buildLimitedQuery("select * from t", 10));
    }
}
