package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.TaskFileSpec;

/**
 * Optional metadata-first access to an endpoint: resolving the audit boundary
 * and reading native metadata (snapshot info, schema, slice hints) without
 * scanning rows. Provided by connectors that advertise
 * {@code supportsNativeMetadata} or {@code supportsSnapshotBoundary}.
 */
public interface MetadataReader {

    /**
     * Reads a metadata snapshot for the resolved boundary: schema, native
     * attributes (for example snapshot ids or manifest counts), and optional
     * slice hints that let the planner localize work without a table scan.
     *
     * @throws Exception if metadata cannot be read; the engine falls back to
     *                   query-based paths where capabilities allow
     */
    MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception;

    /**
     * Resolves the user-configured boundary (for example
     * {@code snapshot: latest}) to a concrete {@link BoundaryRef} with a
     * fingerprint and a stability verdict. A ref with {@code stable=false}
     * makes the engine refuse execution (exit code 5) instead of auditing a
     * moving target.
     *
     * @throws Exception if the boundary cannot be resolved at all
     */
    BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception;
}
