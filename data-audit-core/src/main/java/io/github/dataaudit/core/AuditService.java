package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.SummaryMetrics;

import java.util.List;

public class AuditService {
    public String classify(List<String> schemaIssues, SummaryMetrics sourceSummary, SummaryMetrics targetSummary, DiffResult diff) {
        if (schemaIssues != null && !schemaIssues.isEmpty()) {
            return "schema_mismatch";
        }
        if (sourceSummary != null && targetSummary != null && sourceSummary.rowCount != targetSummary.rowCount) {
            return "row_count_mismatch";
        }
        if (sourceSummary != null && targetSummary != null && !safeEquals(sourceSummary.checksum, targetSummary.checksum)) {
            return "checksum_mismatch";
        }
        if (diff != null && !diff.consistent) {
            return diff.rootCause;
        }
        return "consistent";
    }

    public void applyResumeHint(ReportModel report) {
        if (report.result.suspectSlices != null && !report.result.suspectSlices.isEmpty()) {
            report.result.resumeHint = "data-audit diff -f task.yaml --slice " + report.result.suspectSlices.get(0).sliceKey;
        }
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
