// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcebergConnectorFactoryTest {
    private final IcebergConnectorFactory factory = new IcebergConnectorFactory();

    @Test
    void shouldSupportOnlyIcebergEndpoints() {
        assertTrue(factory.supports(endpointForType("iceberg")));
        assertTrue(factory.supports(endpointForType("ICEBERG")));
        assertFalse(factory.supports(endpointForType("jdbc")));
        assertFalse(factory.supports(null));
        assertEquals("iceberg", factory.type());
    }

    @Test
    void shouldBeDiscoverableThroughServiceLoader() {
        boolean found = false;
        for (ConnectorFactory candidate : ServiceLoader.load(ConnectorFactory.class)) {
            if (candidate instanceof IcebergConnectorFactory) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services must register IcebergConnectorFactory");
    }

    @Test
    void shouldAdvertiseCapabilitiesMatchingWiredReaders() throws Exception {
        TaskFileSpec.EndpointSpec endpoint = endpointForType("iceberg");
        endpoint.location = "/warehouse/db/orders";
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint)) {
            CapabilityDescriptor capabilities = bundle.getCapabilityDescriptor();
            assertEquals("iceberg", capabilities.connectorType);
            assertTrue(capabilities.supportsSnapshotBoundary);
            assertTrue(capabilities.supportsNativeMetadata);
            assertTrue(capabilities.supportsRoutingSignalPushdown);
            assertEquals("metadata_first", capabilities.attributes.get("mode"));

            assertNotNull(bundle.getSchemaReader());
            assertNotNull(bundle.getMetadataReader(), "native metadata flag requires a metadata reader");
            assertNotNull(bundle.getRoutingSignalReader(), "routing pushdown flag requires a routing reader");
            assertNotNull(bundle.getRowStreamReader());
        }
    }

    private TaskFileSpec.EndpointSpec endpointForType(String type) {
        TaskFileSpec.EndpointSpec spec = new TaskFileSpec.EndpointSpec();
        spec.type = type;
        return spec;
    }
}
