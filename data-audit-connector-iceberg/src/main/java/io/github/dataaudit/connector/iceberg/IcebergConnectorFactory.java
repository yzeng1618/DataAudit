package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class IcebergConnectorFactory implements ConnectorFactory {
    @Override
    public String type() {
        return "iceberg";
    }

    @Override
    public boolean supports(TaskFileSpec.EndpointSpec endpointSpec) {
        return endpointSpec != null && "iceberg".equalsIgnoreCase(endpointSpec.type);
    }

    @Override
    public ConnectorBundle open(TaskFileSpec.EndpointSpec endpointSpec) {
        CapabilityDescriptor capabilityDescriptor = new CapabilityDescriptor();
        capabilityDescriptor.connectorType = "iceberg";
        capabilityDescriptor.supportsSnapshotBoundary = true;
        capabilityDescriptor.supportsMetadataStats = true;
        capabilityDescriptor.supportsPartitionPrune = true;
        capabilityDescriptor.supportsColumnProjection = false;
        capabilityDescriptor.supportsKeyedDiff = false;
        capabilityDescriptor.supportsKeylessMultiset = false;
        capabilityDescriptor.attributes.put("mode", "metadata_first");
        return new ConnectorBundle(capabilityDescriptor, null, new ReflectionIcebergMetadataReader(endpointSpec), null);
    }
}
