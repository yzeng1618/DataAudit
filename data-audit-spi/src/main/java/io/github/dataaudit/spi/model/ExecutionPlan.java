package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class ExecutionPlan {
    public String objectClass;
    public String selectedPath;
    public List<String> executedLevels = new ArrayList<>();
    public List<String> decisionTrace = new ArrayList<>();
    public BoundaryRef boundary;
    public String signalBackend;
    public String signalStrategy;
    public String localizationStrategy;
    public String resumeStrategy;
    public String reason;
    public String shortCircuitReason;
    public String refuseReason;
}
