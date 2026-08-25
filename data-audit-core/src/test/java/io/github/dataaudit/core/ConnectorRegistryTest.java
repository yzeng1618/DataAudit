// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorRegistryTest {
    @Test
    void returnsImmutableSortedDistinctConnectorTypes() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(
                factory("trino"),
                factory("jdbc"),
                factory("jdbc")));

        List<String> types = registry.types();

        assertEquals(List.of("jdbc", "trino"), types);
        assertThrows(UnsupportedOperationException.class, () -> types.add("iceberg"));
    }

    private ConnectorFactory factory(String type) {
        return new ConnectorFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public boolean supports(TaskFileSpec.EndpointSpec endpointSpec) {
                return false;
            }

            @Override
            public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
