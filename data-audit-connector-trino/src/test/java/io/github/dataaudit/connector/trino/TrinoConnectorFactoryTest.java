// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.trino;

import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoConnectorFactoryTest {
    private final TrinoConnectorFactory factory = new TrinoConnectorFactory();

    @Test
    void shouldSupportTrinoAndSqlEndpoints() {
        assertTrue(factory.supports(endpointForType("trino")));
        assertTrue(factory.supports(endpointForType("TRINO")));
        assertTrue(factory.supports(endpointForType("sql")));
        assertFalse(factory.supports(endpointForType("jdbc")));
        assertFalse(factory.supports(null));
        assertEquals("trino", factory.type());
    }

    @Test
    void shouldBeDiscoverableThroughServiceLoader() {
        boolean found = false;
        for (ConnectorFactory candidate : ServiceLoader.load(ConnectorFactory.class)) {
            if (candidate instanceof TrinoConnectorFactory) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services must register TrinoConnectorFactory; "
                + "without it the CLI cannot open type: trino/sql endpoints at all");
    }

    @Test
    void shouldRejectMissingQueryConnector() {
        TaskFileSpec spec = new TaskFileSpec();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.open(spec, endpointForType("trino")));
        assertTrue(error.getMessage().contains("query_connector.type=trino"));
    }

    @Test
    void shouldRejectNonTrinoQueryConnector() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "jdbc";
        assertThrows(IllegalArgumentException.class, () -> factory.open(spec, endpointForType("sql")));
    }

    @Test
    void shouldNormalizeJdbcPrefixedUriWithCatalogAndSchema() {
        TaskFileSpec spec = specWithUri("jdbc:trino://host:8080");
        TaskFileSpec.EndpointSpec endpoint = endpointForType("trino");
        endpoint.catalog = "tpch";
        endpoint.schema = "tiny";
        assertEquals("jdbc:trino://host:8080/tpch/tiny", factory.buildJdbcUrl(spec, endpoint));
    }

    @Test
    void shouldEnableSslForHttpsUriAndStripPath() {
        TaskFileSpec spec = specWithUri("https://trino.example.com:8443/extra/path");
        assertEquals("jdbc:trino://trino.example.com:8443?SSL=true",
                factory.buildJdbcUrl(spec, endpointForType("trino")));
    }

    @Test
    void shouldFallBackToQueryConnectorCatalog() {
        TaskFileSpec spec = specWithUri("trino://host:1234");
        spec.queryConnector.catalog = "shared_catalog";
        assertEquals("jdbc:trino://host:1234/shared_catalog",
                factory.buildJdbcUrl(spec, endpointForType("sql")));
    }

    @Test
    void shouldCombineEndpointCatalogWithQueryConnectorSchema() {
        TaskFileSpec spec = specWithUri("http://host:8080");
        spec.queryConnector.schema = "shared_schema";
        TaskFileSpec.EndpointSpec endpoint = endpointForType("trino");
        endpoint.catalog = "cat";
        assertEquals("jdbc:trino://host:8080/cat/shared_schema", factory.buildJdbcUrl(spec, endpoint));
    }

    private TaskFileSpec specWithUri(String uri) {
        TaskFileSpec spec = new TaskFileSpec();
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "trino";
        spec.queryConnector.uri = uri;
        return spec;
    }

    private TaskFileSpec.EndpointSpec endpointForType(String type) {
        TaskFileSpec.EndpointSpec spec = new TaskFileSpec.EndpointSpec();
        spec.type = type;
        return spec;
    }
}
