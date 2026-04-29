package io.github.dataaudit.ai.report;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownReportGeneratorTest {
    @Test
    void shouldRenderTechnicalAcceptanceAndManagementReportsFromDeterministicResult() {
        AuditPlan plan = new AuditPlan();
        AuditPlan.RecommendedStep step = new AuditPlan.RecommendedStep();
        step.type = "partition_checksum";
        step.sqlTemplate = "select dt, count(*) from orders group by dt";
        plan.recommendedSteps.add(step);

        RootCauseAnalysis analysis = new RootCauseAnalysis();
        RootCauseAnalysis.PossibleCause cause = new RootCauseAnalysis.PossibleCause();
        cause.hypothesis = "可能是目标端分区 overwrite 覆盖不完整";
        cause.confidence = 0.82;
        cause.evidence.add("差异集中在 dt=2026-04-24");
        cause.recommendedChecks.add("检查目标端 snapshot");
        cause.missingInformation.add("Iceberg snapshot 明细");
        analysis.possibleRootCauses.add(cause);
        analysis.recommendedChecks.add("检查目标端 snapshot");
        analysis.missingInformation.add("Iceberg snapshot 明细");

        Map<String, Object> result = Map.of(
                "status", "DIFF_FOUND",
                "proof_mode", "GROUPED_CHECKSUM",
                "confidence", "HIGH",
                "diff_partition", "dt=2026-04-24");

        MarkdownReportGenerator generator = new MarkdownReportGenerator();
        String technical = generator.render(plan, result, analysis, "technical");
        String acceptance = generator.render(plan, result, analysis, "acceptance");
        String management = generator.render(plan, result, analysis, "management");

        assertTrue(technical.contains("技术排查版"));
        assertTrue(technical.contains("dt=2026-04-24"));
        assertTrue(technical.contains("select dt, count(*)"));
        assertTrue(technical.contains("推荐确定性检查"));
        assertTrue(technical.contains("检查目标端 snapshot"));
        assertTrue(technical.contains("Iceberg snapshot 明细"));
        assertTrue(acceptance.contains("验收交付版"));
        assertTrue(acceptance.contains("确定性核验发现差异"));
        assertFalse(acceptance.contains("数据一致"));
        assertTrue(management.contains("管理摘要版"));
        assertTrue(management.contains("阻塞"));
    }

    @Test
    void shouldRenderNestedReportModelFacts() {
        AuditPlan plan = new AuditPlan();
        RootCauseAnalysis analysis = new RootCauseAnalysis();
        Map<String, Object> result = Map.of(
                "result", Map.of(
                        "status", "DIFF_FOUND",
                        "proof_mode", "EXACT_DIFF",
                        "confidence", "EXACT",
                        "suspect_slices", List.of(Map.of("slice_key", "dt=2026-04-24"))),
                "evidence", Map.of(
                        "notes", List.of("sink commit success"),
                        "global_signal", Map.of("source_summary", Map.of("row_count", 10))));

        String markdown = new MarkdownReportGenerator().render(plan, result, analysis, "technical");

        assertTrue(markdown.contains("确定性核验发现差异"));
        assertTrue(markdown.contains("EXACT_DIFF"));
        assertTrue(markdown.contains("dt=2026-04-24"));
        assertTrue(markdown.contains("sink commit success"));
    }
}
