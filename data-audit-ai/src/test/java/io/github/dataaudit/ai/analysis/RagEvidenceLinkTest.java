package io.github.dataaudit.ai.analysis;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.report.MarkdownReportGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvidenceLinkTest {
    @Test
    void rootCauseAnalysisShouldCarryRetrievedCaseIdsAndEvidenceLinks() {
        RootCauseAnalysis analysis = new RootCauseAnalyzer(new LocalCaseRetriever())
                .analyze(new AuditPlan(), Map.of(
                        "status", "DIFF_FOUND",
                        "logs", "Doris Stream Load 307 redirect retry rejected rows"));

        RootCauseAnalysis.PossibleCause cause = analysis.possibleRootCauses.get(0);

        assertTrue(analysis.retrievedCases.stream().anyMatch(item -> "doris-stream-load-redirect".equals(item.id)));
        assertTrue(analysis.retrievedCases.stream().anyMatch(item -> !item.matchedEvidence.isEmpty()));
        assertTrue(cause.retrievedCases.stream().anyMatch(item -> "doris-stream-load-redirect".equals(item.id)));
        assertTrue(cause.evidence.stream().anyMatch(item -> item.contains("retrieved_case:doris-stream-load-redirect")));
    }

    @Test
    void markdownReportShouldKeepRagCausesAsHypothesesWithCaseEvidence() {
        RootCauseAnalysis analysis = new RootCauseAnalyzer(new LocalCaseRetriever())
                .analyze(new AuditPlan(), Map.of(
                        "status", "DIFF_FOUND",
                        "logs", "Doris Stream Load 307 redirect retry rejected rows"));

        String markdown = new MarkdownReportGenerator().render(
                new AuditPlan(),
                Map.of("status", "DIFF_FOUND", "proof_mode", "EXACT_DIFF"),
                analysis,
                "technical");

        assertTrue(markdown.contains("假设"));
        assertTrue(markdown.contains("可能"));
        assertTrue(markdown.contains("doris-stream-load-redirect"));
        assertTrue(markdown.contains("matched_evidence"));
        assertFalse(markdown.contains("根因是"));
    }
}
