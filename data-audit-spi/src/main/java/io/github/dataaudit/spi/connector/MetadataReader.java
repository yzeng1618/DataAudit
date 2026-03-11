package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.TaskFileSpec;

public interface MetadataReader {
    MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception;

    BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception;
}

