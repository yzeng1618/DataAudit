package io.github.dataaudit.spi.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
        public String objectClass;
        public String selectedPath;
        public List<String> executedLevels = new ArrayList<>();
        public BoundaryRef boundary;
        public String reason;

        public String getTaskName() {
            return taskName;
        }

        public String getObjectClass() {
            return objectClass;
        }

        public String getSelectedPath() {
            return selectedPath;
        }

        public List<String> getExecutedLevels() {
            return executedLevels;
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
        public String rootCause = "unknown";
        public List<SegmentDescriptor> suspectSegments = new ArrayList<>();
        public String resumeHint;
        public SummaryMetrics sourceSummary;
        public SummaryMetrics targetSummary;
        public List<String> schemaIssues = new ArrayList<>();
        public DiffResult diff = new DiffResult();

        public String getStatus() {
            return status;
        }

        public String getRootCause() {
            return rootCause;
        }

        public List<SegmentDescriptor> getSuspectSegments() {
            return suspectSegments;
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

        public List<String> getSchemaIssues() {
            return schemaIssues;
        }

        public DiffResult getDiff() {
            return diff;
        }
    }

    public static class EvidenceSection {
        public List<String> notes = new ArrayList<>();

        public List<String> getNotes() {
            return notes;
        }
    }
}
