package io.github.dataaudit.ai.guardrail;

import io.github.dataaudit.ai.model.AuditPlan;

import java.util.ArrayList;
import java.util.List;

public class AuditPlanGuardrail {
    private final ArtifactStructureValidator structureValidator;
    private final RequiredFieldGuardrail requiredFieldGuardrail;
    private final SqlSafetyChecker sqlSafetyChecker;

    public AuditPlanGuardrail() {
        this(new ArtifactStructureValidator(), new RequiredFieldGuardrail(), new SqlSafetyChecker());
    }

    public AuditPlanGuardrail(ArtifactStructureValidator structureValidator,
                              RequiredFieldGuardrail requiredFieldGuardrail,
                              SqlSafetyChecker sqlSafetyChecker) {
        this.structureValidator = structureValidator;
        this.requiredFieldGuardrail = requiredFieldGuardrail;
        this.sqlSafetyChecker = sqlSafetyChecker;
    }

    public AuditPlan apply(AuditPlan plan) {
        structureValidator.validate(plan);
        ensureBaselineChecks(plan);
        removeUnsafeSql(plan);
        syncMappings(plan);
        requiredFieldGuardrail.validate(plan);
        return plan;
    }

    private void ensureBaselineChecks(AuditPlan plan) {
        if (plan.recommendedSteps.stream().noneMatch(step -> "global_row_count".equals(step.type))) {
            plan.recommendedSteps.add(baseline("global_row_count", "global_row_count", "Global row count check",
                    "select count(*) as row_count from ${table}", "summary_engine", 0.97));
            plan.plannerTrace.guardrailActions.add("inserted global_row_count");
        }
        if (plan.recommendedSteps.stream().noneMatch(step -> "global_checksum".equals(step.type))) {
            plan.recommendedSteps.add(baseline("global_checksum", "global_checksum", "Global checksum check",
                    "select checksum(${columns}) as checksum from ${table}", "summary_engine", 0.94));
            plan.plannerTrace.guardrailActions.add("inserted global_checksum");
        }
    }

    private AuditPlan.RecommendedStep baseline(String id, String type, String description, String sql,
                                               String capability, double confidence) {
        AuditPlan.RecommendedStep step = new AuditPlan.RecommendedStep();
        step.id = id;
        step.type = type;
        step.description = description;
        step.triggerCondition = "guardrail baseline";
        step.sqlTemplate = sql;
        step.confidence = confidence;
        step.evidence.add("guardrail_inserted");
        step.missingInformation.add("none");
        step.mapping.stepId = id;
        step.mapping.capability = capability;
        step.mapping.status = "SUPPORTED";
        step.mapping.notes = "inserted deterministic baseline";
        step.mapping.executionPlanRefs.add("TaskFileSpec");
        step.mapping.executionPlanRefs.add("ExecutionPlan");
        return step;
    }

    private void removeUnsafeSql(AuditPlan plan) {
        List<AuditPlan.RecommendedStep> safe = new ArrayList<>();
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (sqlSafetyChecker.isSafe(step.sqlTemplate) && sqlSafetyChecker.isSafe(step.dslTemplate)) {
                safe.add(step);
            } else {
                plan.plannerTrace.guardrailActions.add("removed unsafe step:" + step.id);
            }
        }
        plan.recommendedSteps.clear();
        plan.recommendedSteps.addAll(safe);
    }

    private void syncMappings(AuditPlan plan) {
        plan.dataAuditMapping.clear();
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (step.mapping.stepId == null || step.mapping.stepId.isBlank()) {
                step.mapping.stepId = step.id;
            }
            plan.dataAuditMapping.add(step.mapping);
        }
    }
}
