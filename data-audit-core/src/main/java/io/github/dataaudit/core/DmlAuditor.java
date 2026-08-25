// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class DmlAuditor {
    public ReportModel.DmlAuditSection audit(TaskFileSpec spec,
                                             SummaryMetrics sourceSummary,
                                             SummaryMetrics targetSummary,
                                             DiffResult diff) {
        ReportModel.DmlAuditSection section = new ReportModel.DmlAuditSection();
        section.insertStrategy = spec.semantics.dml.insert;
        section.updateStrategy = spec.semantics.dml.update;
        section.deleteStrategy = spec.semantics.dml.delete.mode;
        section.mergeStrategy = spec.semantics.dml.merge;
        section.decisionTrace.add("insert strategy: " + section.insertStrategy);
        section.decisionTrace.add("update strategy: " + section.updateStrategy);
        section.decisionTrace.add("delete strategy: " + section.deleteStrategy);
        section.decisionTrace.add("merge strategy: " + section.mergeStrategy);

        if (diff == null || diff.consistent) {
            section.verdict = "consistent";
            section.decisionTrace.add("no row-level DML anomaly detected");
            return section;
        }

        if (hasSampleType(diff, "extra_in_target")) {
            section.verdict = deleteVerdict(spec);
            section.decisionTrace.add("target still contains rows that should have been removed or marked");
            return section;
        }
        if (hasSampleType(diff, "missing_in_target")) {
            section.verdict = "insert_incomplete";
            section.decisionTrace.add("source rows are missing in target under insert/completeness semantics");
            return section;
        }
        if (hasSampleType(diff, "row_mismatch")) {
            section.verdict = latestStateVerdict(spec);
            section.decisionTrace.add("same business key resolved to different latest-state row values");
            return section;
        }
        if (hasSampleType(diff, "multiset_mismatch") || hasSampleType(diff, "multiset_extra_target")) {
            section.verdict = sourceSummary != null && targetSummary != null && sourceSummary.rowCount > targetSummary.rowCount
                    ? "insert_incomplete"
                    : "keyless_multiset_mismatch";
            section.decisionTrace.add("keyless multiset compare detected count drift for canonical rows");
            return section;
        }

        section.verdict = diff.rootCause == null ? "data_state_mismatch" : diff.rootCause;
        section.decisionTrace.add("fallback to diff-engine root cause: " + section.verdict);
        return section;
    }

    public String classify(TaskFileSpec spec,
                           SummaryMetrics sourceSummary,
                           SummaryMetrics targetSummary,
                           DiffResult diff) {
        if (sourceSummary != null && targetSummary != null && sourceSummary.rowCount != targetSummary.rowCount && (diff == null || diff.consistent)) {
            return "row_count_mismatch";
        }
        if (sourceSummary != null && targetSummary != null && !safeEquals(sourceSummary.checksum, targetSummary.checksum) && (diff == null || diff.consistent)) {
            return "checksum_mismatch";
        }
        return audit(spec, sourceSummary, targetSummary, diff).verdict;
    }

    public void applyResumeHint(ReportModel report) {
        if (report.result.suspectSlices != null && !report.result.suspectSlices.isEmpty()) {
            report.result.resumeHint = "data-audit diff -f task.yaml --slice " + report.result.suspectSlices.get(0).sliceKey;
        }
    }

    private boolean hasSampleType(DiffResult diff, String sampleType) {
        for (DiffResult.DiffSample sample : diff.samples) {
            if (sampleType.equalsIgnoreCase(sample.type)) {
                return true;
            }
        }
        return false;
    }

    private String deleteVerdict(TaskFileSpec spec) {
        if ("soft_delete".equalsIgnoreCase(spec.semantics.dml.delete.mode)) {
            return "soft_delete_mismatch";
        }
        if ("delete_marker".equalsIgnoreCase(spec.semantics.dml.delete.mode)) {
            return "delete_marker_mismatch";
        }
        return "delete_not_effective";
    }

    private String latestStateVerdict(TaskFileSpec spec) {
        if ("latest_state".equalsIgnoreCase(spec.semantics.dml.merge)) {
            return "latest_state_mismatch";
        }
        if ("latest_state".equalsIgnoreCase(spec.semantics.dml.update)) {
            return "latest_state_mismatch";
        }
        return "row_value_mismatch";
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
