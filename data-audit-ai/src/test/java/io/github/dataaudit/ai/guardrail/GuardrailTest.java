// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.guardrail;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailTest {
    @Test
    void shouldRejectMutationSqlTemplates() {
        SqlSafetyChecker checker = new SqlSafetyChecker();

        assertTrue(checker.isSafe("select count(*) from orders"));
        assertFalse(checker.isSafe("delete from orders where dt = '2026-04-24'"));
        assertThrows(IllegalArgumentException.class,
                () -> checker.requireSafe("merge into target using source on target.id = source.id"));
    }

    @Test
    void shouldRequireConfidenceEvidenceAndMissingInformation() {
        AuditPlan plan = new AuditPlan();
        plan.tableClassification.tableType = "small_table";
        plan.tableClassification.scaleClass = "small";
        plan.tableClassification.confidence = 0.9;
        plan.tableClassification.evidence.add("test");
        plan.tableClassification.missingInformation.add("none");
        AuditPlan.RecommendedStep step = new AuditPlan.RecommendedStep();
        step.id = "global_row_count";
        step.type = "global_row_count";
        step.confidence = 0.91;
        step.evidence.add("baseline deterministic check");
        plan.recommendedSteps.add(step);

        assertThrows(IllegalArgumentException.class, () -> new RequiredFieldGuardrail().validate(plan));

        step.missingInformation.add("none");

        new RequiredFieldGuardrail().validate(plan);
    }

    @Test
    void shouldExposeCompactResponseSchemaContracts() {
        Map<String, Object> planSchema = JsonSchemas.schemaFor(AuditPlan.class);
        Map<String, Object> analysisSchema = JsonSchemas.schemaFor(RootCauseAnalysis.class);

        assertEquals("AuditPlan", planSchema.get("title"));
        assertTrue(String.valueOf(planSchema).contains("recommended_steps"));
        assertTrue(String.valueOf(planSchema).contains("SUPPORTED"));
        assertEquals("RootCauseAnalysis", analysisSchema.get("title"));
        assertTrue(String.valueOf(analysisSchema).contains("possible_root_causes"));
    }
}
