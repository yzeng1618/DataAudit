package io.github.dataaudit.spi.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportModel {
    public String runId;
    public OffsetDateTime generatedAt = OffsetDateTime.now();
    public PlanSection plan = new PlanSection();
    public ResultSection result = new ResultSection();
    public EvidenceSection evidence = new EvidenceSection();

    public String getRunId() {
        return runId;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public PlanSection getPlan() {
        return plan;
    }

    public ResultSection getResult() {
        return result;
    }

    public EvidenceSection getEvidence() {
        return evidence;
    }

    public static class PlanSection {
        public String taskName;
        public ScaleClass scaleClass;
        public String signalStrategy;
        public String localizationStrategy;
        public List<String> decisionTrace = new ArrayList<>();
        public BoundaryRef boundary;
        public String reason;

        public String getTaskName() {
            return taskName;
        }

        public String getSignalStrategy() {
            return signalStrategy;
        }

        public String getLocalizationStrategy() {
            return localizationStrategy;
        }

        public List<String> getDecisionTrace() {
            return decisionTrace;
        }

        public BoundaryRef getBoundary() {
            return boundary;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class ResultSection {
        public String status = "UNKNOWN";
        public String rootCause;
        public ProofMode proofMode;
        public ConfidenceLevel confidence;
        public boolean noKeyMode;
        public String fallbackReason;
        public SamplingSummary samplingSummary = new SamplingSummary();
        public List<SliceDescriptor> suspectSlices = new ArrayList<>();
        public String resumeHint;
        public SummaryMetrics sourceSummary;
        public SummaryMetrics targetSummary;
        public DiffResult diff = new DiffResult();

        public String getStatus() {
            return status;
        }

        public String getRootCause() {
            return rootCause;
        }

        public SamplingSummary getSamplingSummary() {
            return samplingSummary;
        }

        public List<SliceDescriptor> getSuspectSlices() {
            return suspectSlices;
        }

        public String getResumeHint() {
            return resumeHint;
        }

        public SummaryMetrics getSourceSummary() {
            return sourceSummary;
        }

        public SummaryMetrics getTargetSummary() {
            return targetSummary;
        }

        public DiffResult getDiff() {
            return diff;
        }
    }

    public static class EvidenceSection {
        public List<String> notes = new ArrayList<>();
        public GlobalSignalEvidence globalSignal = new GlobalSignalEvidence();
        public LocalizationEvidence localization = new LocalizationEvidence();
        public ExactDiffEvidence exactDiff = new ExactDiffEvidence();

        public List<String> getNotes() {
            return notes;
        }
    }

    // Compatibility-only types for internal auditors. They are intentionally not
    // referenced from ResultSection, so they do not appear in the public report schema.
    public static class DmlAuditSection {
        public String insertStrategy;
        public String updateStrategy;
        public String deleteStrategy;
        public String mergeStrategy;
        public String verdict;
        public List<String> decisionTrace = new ArrayList<>();
    }

    public static class DdlAuditSection {
        public String mode;
        public String partitionEvolution;
        public Map<String, String> renameMapping = new LinkedHashMap<>();
        public List<String> typeRules = new ArrayList<>();
        public String verdict;
        public List<String> decisionTrace = new ArrayList<>();
    }
}
