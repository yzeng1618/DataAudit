// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class BoundaryResolver {
    public BoundaryRef resolve(TaskFileSpec spec, ConnectorBundle sourceBundle, ConnectorBundle targetBundle) throws Exception {
        String boundaryType = spec.boundary.type == null ? "job_finish" : spec.boundary.type;
        if ("snapshot".equalsIgnoreCase(boundaryType)) {
            MetadataReader reader = preferredMetadataReader(targetBundle, sourceBundle);
            if (reader == null) {
                BoundaryRef ref = new BoundaryRef();
                ref.type = boundaryType;
                ref.reference = spec.boundary.reference;
                ref.stable = false;
                ref.fingerprint = "unsupported-snapshot";
                ref.detail = "snapshot boundary requested but no metadata reader available";
                return ref;
            }
            return reader.resolveBoundary(spec.boundary);
        }

        BoundaryRef ref = new BoundaryRef();
        ref.type = boundaryType;
        ref.reference = spec.boundary.reference;
        ref.stable = true;
        ref.fingerprint = fingerprint(spec.task.name + "|" + boundaryType + "|" + spec.boundary.reference);
        ref.detail = "logical boundary";
        return ref;
    }

    private MetadataReader preferredMetadataReader(ConnectorBundle primary, ConnectorBundle secondary) {
        if (primary != null
                && primary.getMetadataReader() != null
                && primary.getCapabilityDescriptor() != null
                && primary.getCapabilityDescriptor().supportsSnapshotBoundary) {
            return primary.getMetadataReader();
        }
        if (secondary != null
                && secondary.getMetadataReader() != null
                && secondary.getCapabilityDescriptor() != null
                && secondary.getCapabilityDescriptor().supportsSnapshotBoundary) {
            return secondary.getMetadataReader();
        }
        if (primary != null && primary.getMetadataReader() != null) {
            return primary.getMetadataReader();
        }
        if (secondary != null) {
            return secondary.getMetadataReader();
        }
        return null;
    }

    private String fingerprint(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte current : bytes) {
            builder.append(String.format("%02x", current));
        }
        return builder.toString();
    }
}
