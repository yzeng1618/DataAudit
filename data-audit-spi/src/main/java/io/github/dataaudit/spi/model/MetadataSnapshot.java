package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MetadataSnapshot {
    public BoundaryRef boundary;
    public SchemaModel schema = new SchemaModel();
    public Map<String, String> attributes = new LinkedHashMap<>();
    public List<SegmentDescriptor> segmentHints = new ArrayList<>();
}

