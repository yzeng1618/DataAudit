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
    public List<SliceState> slices = new ArrayList<>();

    public static class SliceState {
        public String sliceKey;
        public String status;
        public String resumeToken;
    }
}
