package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class ReflectionIcebergMetadataReader implements MetadataReader {
    private final IcebergTableSupport tableSupport;

    public ReflectionIcebergMetadataReader(TaskFileSpec.EndpointSpec endpointSpec) {
        this.tableSupport = new IcebergTableSupport(endpointSpec);
    }

    @Override
    public MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        return tableSupport.readMetadata(boundarySpec);
    }

    @Override
    public BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        return tableSupport.resolveBoundary(boundarySpec);
    }
}
