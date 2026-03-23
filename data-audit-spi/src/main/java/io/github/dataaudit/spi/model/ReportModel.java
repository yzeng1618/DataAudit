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
        public String objectClass;
        public String selectedPath;
        public String signalBackend;
        public String signalStrategy;
        public String localizationStrategy;
        public List<String> executedLevels = new ArrayList<>();
        public List<String> decisionTrace = new ArrayList<>();
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

        public String getSignalBackend() {
            return signalBackend;
        }

        public String getSignalStrategy() {
            return signalStrategy;
        }

        public String getLocalizationStrategy() {
            return localizationStrategy;
        }

        public List<String> getExecutedLevels() {
            return executedLevels;
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
        public String rootCause = "unknown";
        public String consistencyLevel = "unknown";
        public String verdictBasis = "unknown";
        public String signalBackend = "unknown";
        public String inconclusiveReason;
        public SamplingSummary samplingSummary = new SamplingSummary();
        public List<SliceDescriptor> suspectSlices = new ArrayList<>();
        public String resumeHint;
        public SummaryMetrics sourceSummary;
        public SummaryMetrics targetSummary;
        public List<String> schemaIssues = new ArrayList<>();
        public List<String> decisionTrace = new ArrayList<>();
        public DiffResult diff = new DiffResult();
        public DmlAuditSection dmlAudit = new DmlAuditSection();
        public DdlAuditSection ddlAudit = new DdlAuditSection();

        public String getStatus() {
            return status;
        }

        public String getRootCause() {
            return rootCause;
        }

        public String getConsistencyLevel() {
            return consistencyLevel;
        }

        public String getVerdictBasis() {
            return verdictBasis;
        }

        public String getSignalBackend() {
            return signalBackend;
        }

        public String getInconclusiveReason() {
            return inconclusiveReason;
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

        public List<String> getSchemaIssues() {
            return schemaIssues;
        }

        public List<String> getDecisionTrace() {
            return decisionTrace;
        }

        public DiffResult getDiff() {
            return diff;
        }

        public DmlAuditSection getDmlAudit() {
            return dmlAudit;
        }

        public DdlAuditSection getDdlAudit() {
            return ddlAudit;
        }
    }

    public static class EvidenceSection {
        public List<String> notes = new ArrayList<>();

        public List<String> getNotes() {
            return notes;
        }
    }

    public static class DmlAuditSection {
        public String insertStrategy;
        public String updateStrategy;
        public String deleteStrategy;
        public String mergeStrategy;
        public String verdict = "unknown";
        public List<String> decisionTrace = new ArrayList<>();

        public String getInsertStrategy() {
            return insertStrategy;
        }

        public String getUpdateStrategy() {
            return updateStrategy;
        }

        public String getDeleteStrategy() {
            return deleteStrategy;
        }

        public String getMergeStrategy() {
            return mergeStrategy;
        }

        public String getVerdict() {
            return verdict;
        }

        public List<String> getDecisionTrace() {
            return decisionTrace;
        }
    }

    public static class DdlAuditSection {
        public String mode;
        public String partitionEvolution;
        public String verdict = "unknown";
        public Map<String, String> renameMapping = new LinkedHashMap<>();
        public List<String> typeRules = new ArrayList<>();
        public List<String> decisionTrace = new ArrayList<>();

        public String getMode() {
            return mode;
        }

        public String getPartitionEvolution() {
            return partitionEvolution;
        }

        public String getVerdict() {
            return verdict;
        }

        public Map<String, String> getRenameMapping() {
            return renameMapping;
        }

        public List<String> getTypeRules() {
            return typeRules;
        }

        public List<String> getDecisionTrace() {
            return decisionTrace;
        }
    }
}
