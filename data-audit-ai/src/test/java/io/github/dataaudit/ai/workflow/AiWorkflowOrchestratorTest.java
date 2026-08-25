// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.workflow;

import io.github.dataaudit.ai.guardrail.ArtifactStructureValidator;
import io.github.dataaudit.ai.guardrail.AuditPlanGuardrail;
import io.github.dataaudit.ai.guardrail.RequiredFieldGuardrail;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.ai.planner.AiStrategyPlanner;
import io.github.dataaudit.ai.provider.AiClient;
import io.github.dataaudit.ai.provider.MockAiClient;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkflowOrchestratorTest {
    @Test
    void disabledProviderUsesRuleFallback() {
        AuditPlan plan = new PlanningOrchestrator(new AiWorkflowConfig(), new LocalCaseRetriever())
                .plan(profile());

        assertEquals("disabled", plan.plannerTrace.aiProvider);
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "global_row_count".equals(step.type)));
        assertTrue(plan.plannerTrace.decisions.contains("rule_fallback_provider_disabled"));
        assertTrue(plan.retrievedCases.stream().anyMatch(item -> item.contains("oracle-decimal-drift")));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "decimal_precision_check".equals(step.type)));
    }

    @Test
    void mockProviderProposalIsGuardedAndBaselineIsInserted() {
        AuditPlan proposal = minimalProviderPlan();
        proposal.recommendedSteps.add(step("metric_sum_amount", "metric_sum",
                "select sum(amount) from ${table}", "PARTIAL"));
        MockAiClient client = new MockAiClient().register(AuditPlan.class, proposal);
        AiWorkflowConfig config = new AiWorkflowConfig("mock", null, null);

        AuditPlan plan = new PlanningOrchestrator(config, client, new LocalCaseRetriever(),
                new AiStrategyPlanner(), new AuditPlanGuardrail(), new ArtifactStructureValidator())
                .plan(profile());

        assertEquals("mock", plan.plannerTrace.aiProvider);
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "global_row_count".equals(step.type)));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "global_checksum".equals(step.type)));
    }

    @Test
    void unsafeSqlFromProviderIsRemoved() {
        AuditPlan proposal = minimalProviderPlan();
        proposal.recommendedSteps.add(step("bad", "dangerous", "delete from target", "UNSUPPORTED"));
        MockAiClient client = new MockAiClient().register(AuditPlan.class, proposal);
        AiWorkflowConfig config = new AiWorkflowConfig("mock", null, null);

        AuditPlan plan = new PlanningOrchestrator(config, client, new LocalCaseRetriever(),
                new AiStrategyPlanner(), new AuditPlanGuardrail(), new ArtifactStructureValidator())
                .plan(profile());

        assertFalse(plan.recommendedSteps.stream().anyMatch(step -> "bad".equals(step.id)));
        assertTrue(plan.plannerTrace.guardrailActions.stream().anyMatch(action -> action.contains("removed unsafe step")));
    }

    @Test
    void providerFailureFallsBackToRulePlanner() {
        AiWorkflowConfig config = new AiWorkflowConfig("mock", null, null);
        AiClient failing = new FailingClient();

        AuditPlan plan = new PlanningOrchestrator(config, failing, new LocalCaseRetriever(),
                new AiStrategyPlanner(), new AuditPlanGuardrail(), new ArtifactStructureValidator())
                .plan(profile());

        assertEquals("fallback:failing", plan.plannerTrace.aiProvider);
        assertTrue(plan.plannerTrace.guardrailActions.stream().anyMatch(action -> action.startsWith("provider_failed")));
    }

    @Test
    void factoryShouldCreateOpenAiSdkProviderWithoutNetworkCall() {
        AiWorkflowConfig config = new AiWorkflowConfig("openai-sdk", null, "test-key", "gpt-5.2");

        assertEquals("openai-sdk", new AiClientFactory().create(config).name());
    }

    @Test
    void rootCauseProviderProposalIsValidated() {
        RootCauseAnalysis proposal = new RootCauseAnalysis();
        proposal.anomalySummary = "deterministic status is DIFF_FOUND";
        RootCauseAnalysis.PossibleCause cause = new RootCauseAnalysis.PossibleCause();
        cause.hypothesis = "可能是 overwrite 分区覆盖范围不完整";
        cause.confidence = 0.8;
        cause.evidence.add("write_mode=overwrite");
        cause.recommendedChecks.add("检查目标端 snapshot");
        cause.missingInformation.add("target snapshot files");
        proposal.possibleRootCauses.add(cause);
        MockAiClient client = new MockAiClient().register(RootCauseAnalysis.class, proposal);
        AiWorkflowConfig config = new AiWorkflowConfig("mock", null, null);

        RootCauseAnalysis analysis = new RootCauseOrchestrator(config,
                client,
                new LocalCaseRetriever(),
                new io.github.dataaudit.ai.analysis.AnomalyExtractor(),
                new io.github.dataaudit.ai.analysis.RootCauseAnalyzer(new LocalCaseRetriever()),
                new ArtifactStructureValidator(),
                new RequiredFieldGuardrail())
                .analyze(new AuditPlan(), Map.of("status", "DIFF_FOUND"));

        assertEquals("deterministic status is DIFF_FOUND", analysis.anomalySummary);
        assertFalse(analysis.possibleRootCauses.isEmpty());
    }

    private TableProfile profile() {
        TableProfile profile = new TableProfile();
        profile.source.type = "oracle";
        profile.source.table = "ods.orders";
        profile.target.type = "iceberg";
        profile.target.table = "dw.orders";
        profile.syncContext.writeMode = "overwrite";
        profile.syncContext.syncMode = "batch";
        profile.statistics.estimatedRows = 5_000_000L;
        profile.overrides.partitionFields.add("dt");
        profile.columns.add(column("order_id", "varchar"));
        profile.columns.add(column("amount", "decimal(20,2)"));
        profile.columns.add(column("dt", "date"));
        return profile;
    }

    private TableProfile.ColumnProfile column(String name, String type) {
        TableProfile.ColumnProfile column = new TableProfile.ColumnProfile();
        column.name = name;
        column.type = type;
        return column;
    }

    private AuditPlan minimalProviderPlan() {
        AuditPlan plan = new AuditPlan();
        plan.tableClassification.tableType = "large_table";
        plan.tableClassification.scaleClass = "large";
        plan.tableClassification.confidence = 0.8;
        plan.tableClassification.evidence.add("provider");
        plan.tableClassification.missingInformation.add("none");
        return plan;
    }

    private AuditPlan.RecommendedStep step(String id, String type, String sql, String status) {
        AuditPlan.RecommendedStep step = new AuditPlan.RecommendedStep();
        step.id = id;
        step.type = type;
        step.description = id;
        step.triggerCondition = "provider";
        step.sqlTemplate = sql;
        step.confidence = 0.8;
        step.evidence.add("provider");
        step.missingInformation.add("none");
        step.mapping.stepId = id;
        step.mapping.status = status;
        step.mapping.capability = "provider_template";
        return step;
    }

    private static class FailingClient implements AiClient {
        @Override
        public <T> T generateJson(String operation, Object input, Class<T> responseType) {
            throw new IllegalStateException("boom");
        }

        @Override
        public String name() {
            return "failing";
        }
    }
}
