package io.github.dataaudit.ai.repair;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RepairPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairPlannerTest {
    @Test
    void shouldGenerateSafeConfigAndManualDataRepairActions() {
        AuditPlan plan = new AuditPlan();
        plan.missingInformation.add("normalize.timezone");
        RootCauseAnalysis analysis = new RootCauseAnalysis();
        RootCauseAnalysis.PossibleCause cause = new RootCauseAnalysis.PossibleCause();
        cause.hypothesis = "可能是目标端分区 overwrite 覆盖范围不完整";
        cause.confidence = 0.8;
        cause.evidence.add("overwrite");
        cause.recommendedChecks.add("检查 snapshot");
        cause.missingInformation.add("target snapshot files");
        analysis.possibleRootCauses.add(cause);

        RepairPlan repairPlan = new RepairPlanner().plan(plan, Map.of("status", "DIFF_FOUND"), analysis);

        assertTrue(repairPlan.actions.stream().anyMatch(action -> "config_patch".equals(action.type)));
        assertTrue(repairPlan.actions.stream().anyMatch(action -> "manual_data_fix".equals(action.type)));
        assertTrue(repairPlan.actions.stream()
                .filter(action -> "manual_data_fix".equals(action.type))
                .noneMatch(action -> action.autoExecutable));
    }
}
