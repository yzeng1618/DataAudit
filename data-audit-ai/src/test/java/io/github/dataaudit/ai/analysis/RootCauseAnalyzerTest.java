package io.github.dataaudit.ai.analysis;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootCauseAnalyzerTest {
    @Test
    void shouldAnalyzePartitionDiffWithRetrievedOverwriteCase() {
        AuditPlan plan = new AuditPlan();
        plan.syncContext.writeMode = "overwrite";
        Map<String, Object> result = Map.of(
                "status", "DIFF_FOUND",
                "source_count", 500000,
                "target_count", 499800,
                "diff_partition", "dt=2026-04-24",
                "logs", "overwrite partition dt=2026-04-24 sink commit success");

        RootCauseAnalysis analysis = new RootCauseAnalyzer(new LocalCaseRetriever()).analyze(plan, result);

        assertFalse(analysis.possibleRootCauses.isEmpty());
        assertTrue(analysis.possibleRootCauses.get(0).hypothesis.contains("可能"));
        assertTrue(analysis.possibleRootCauses.get(0).confidence > 0.5);
        assertFalse(analysis.possibleRootCauses.get(0).evidence.isEmpty());
        assertFalse(analysis.possibleRootCauses.get(0).retrievedCases.isEmpty());
        assertTrue(analysis.aiSafetyNotice.contains("不能替代 DataAudit"));
    }

    @Test
    void shouldAnalyzeEmbeddingDimensionMismatch() {
        AuditPlan plan = new AuditPlan();
        Map<String, Object> result = Map.of(
                "status", "DIFF_FOUND",
                "checksum_equal", false,
                "logs", "embedding_dim expected=1536 actual=1024");

        RootCauseAnalysis analysis = new RootCauseAnalyzer(new LocalCaseRetriever()).analyze(plan, result);

        assertTrue(analysis.retrievedCases.stream().anyMatch(c -> c.title.contains("embedding")));
        assertTrue(analysis.recommendedChecks.stream().anyMatch(check -> check.contains("embedding_dim")));
    }

    @Test
    void shouldRankDecimalAndDorisCaseFamilies() {
        AuditPlan decimalPlan = new AuditPlan();
        AuditPlan.RiskItem risk = new AuditPlan.RiskItem();
        risk.riskType = "decimal_precision_risk";
        risk.field = "amount";
        risk.confidence = 0.8;
        risk.evidence.add("decimal");
        risk.missingInformation.add("scale");
        decimalPlan.riskAnalysis.add(risk);

        RootCauseAnalysis decimalAnalysis = new RootCauseAnalyzer(new LocalCaseRetriever())
                .analyze(decimalPlan, Map.of("status", "DIFF_FOUND", "checksum_equal", false));

        assertTrue(decimalAnalysis.possibleRootCauses.stream()
                .anyMatch(cause -> cause.hypothesis.contains("decimal precision")));

        RootCauseAnalysis dorisAnalysis = new RootCauseAnalyzer(new LocalCaseRetriever())
                .analyze(new AuditPlan(), Map.of(
                        "status", "DIFF_FOUND",
                        "logs", "Doris Stream Load 307 redirect retry rejected rows"));

        assertTrue(dorisAnalysis.possibleRootCauses.stream()
                .anyMatch(cause -> cause.hypothesis.contains("Doris Stream Load")));
    }

    @Test
    void shouldExtractSignalsFromNestedDataAuditReport() {
        AuditPlan plan = new AuditPlan();
        plan.syncContext.writeMode = "overwrite";
        Map<String, Object> report = Map.of(
                "result", Map.of(
                        "status", "DIFF_FOUND",
                        "proof_mode", "GROUPED_CHECKSUM",
                        "confidence", "HIGH",
                        "suspect_slices", java.util.List.of(Map.of("key", "dt=2026-04-24")),
                        "source_summary", Map.of("row_count", 500000),
                        "target_summary", Map.of("row_count", 499800)),
                "evidence", Map.of(
                        "notes", java.util.List.of("overwrite partition dt=2026-04-24 sink commit success")));

        RootCauseAnalysis analysis = new RootCauseAnalyzer(new LocalCaseRetriever()).analyze(plan, report);

        assertTrue(analysis.anomalySummary.contains("DIFF_FOUND"));
        assertTrue(analysis.anomalySummary.contains("dt=2026-04-24"));
        assertTrue(analysis.possibleRootCauses.get(0).hypothesis.contains("overwrite"));
    }
}
