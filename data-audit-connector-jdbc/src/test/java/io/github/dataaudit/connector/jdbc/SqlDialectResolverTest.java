package io.github.dataaudit.connector.jdbc;

import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlDialectResolverTest {
    private final SqlDialectResolver resolver = new SqlDialectResolver();

    @Test
    void shouldInferHiveDialectFromOption() {
        TaskFileSpec.EndpointSpec endpoint = new TaskFileSpec.EndpointSpec();
        endpoint.type = "jdbc";
        endpoint.options.put("dialect", "hive");

        SqlDialect dialect = resolver.resolve(endpoint);

        assertInstanceOf(HiveDialect.class, dialect);
    }

    @Test
    void shouldInferDorisDialectFromUrl() {
        TaskFileSpec.EndpointSpec endpoint = new TaskFileSpec.EndpointSpec();
        endpoint.type = "jdbc";
        endpoint.url = "jdbc:doris://localhost:9030/demo";

        SqlDialect dialect = resolver.resolve(endpoint);

        assertInstanceOf(DorisDialect.class, dialect);
    }
}
