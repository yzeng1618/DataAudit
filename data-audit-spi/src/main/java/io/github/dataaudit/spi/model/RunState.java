package io.github.dataaudit.spi.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class RunState {
    public String runId;
    public String taskName;
    public String boundaryFingerprint;
    public String selectedPath;
    public String status;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public String reportJsonPath;
    public String reportHtmlPath;
    public List<SegmentState> segments = new ArrayList<>();

    public static class SegmentState {
        public String segmentKey;
        public String status;
        public String resumeToken;
        public String sourceDigest;
        public String targetDigest;
    }
}

