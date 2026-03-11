package io.github.dataaudit.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class CapabilityDescriptor {
    public String connectorType;
    public boolean supportsSnapshotBoundary;
    public boolean supportsPartitionPrune;
    public boolean supportsColumnProjection;
    public boolean supportsMetadataStats;
    public boolean supportsKeyedDiff = true;
    public boolean supportsKeylessMultiset = true;
    public Map<String, String> attributes = new LinkedHashMap<String, String>();
}
