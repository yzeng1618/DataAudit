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
    public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
        IcebergTableSupport tableSupport = new IcebergTableSupport(endpointSpec);
        CapabilityDescriptor capabilityDescriptor = new CapabilityDescriptor();
        capabilityDescriptor.connectorType = "iceberg";
        capabilityDescriptor.supportsSnapshotBoundary = true;
        capabilityDescriptor.supportsMetadataStats = true;
        capabilityDescriptor.supportsPartitionPrune = true;
        capabilityDescriptor.supportsColumnProjection = true;
        capabilityDescriptor.supportsSignalPushdown = false;
        capabilityDescriptor.supportsGroupedSignalPushdown = false;
        capabilityDescriptor.supportsNativeMetadata = true;
        capabilityDescriptor.supportsKeyedDiff = true;
        capabilityDescriptor.supportsKeylessMultiset = true;
        capabilityDescriptor.sourceLoadPolicy = "balanced";
        capabilityDescriptor.attributes.put("mode", "metadata_first");
        IcebergDataReader endpoint = new IcebergDataReader(spec, tableSupport);
        return new ConnectorBundle(
                capabilityDescriptor,
                endpoint,
                endpoint,
                endpoint,
                new ReflectionIcebergMetadataReader(endpointSpec),
                null
        );
    }
}
