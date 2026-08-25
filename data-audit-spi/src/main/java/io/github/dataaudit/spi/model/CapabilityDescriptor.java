package io.github.dataaudit.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-description of an opened connector. The planner reads these flags to
 * choose the cheapest audit path the endpoint can support; a flag must only be
 * set when the corresponding reader in the
 * {@link io.github.dataaudit.spi.connector.ConnectorBundle} is present and
 * functional. {@code attributes} carries free-form diagnostics that end up in
 * doctor output and reports.
 */
public class CapabilityDescriptor {
    public String connectorType;
    public boolean supportsSnapshotBoundary;
    public boolean supportsPartitionPrune;
    public boolean supportsColumnProjection;
    public boolean supportsMetadataStats;
    public boolean supportsSignalPushdown;
    public boolean supportsGroupedSignalPushdown;
    public boolean supportsRoutingSignalPushdown;
    public boolean supportsNativeMetadata;
    public boolean supportsKeyedDiff = true;
    public boolean supportsKeylessMultiset = true;
    public String sourceLoadPolicy = "balanced";
    public Map<String, String> attributes = new LinkedHashMap<String, String>();
}
