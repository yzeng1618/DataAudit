package io.github.dataaudit.ai.guardrail;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.model.TableProfile;

public class ArtifactStructureValidator {
    public void validate(TableProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("table_profile is required");
        }
        require(profile.source, "profile.source");
        require(profile.target, "profile.target");
        require(profile.columns, "profile.columns");
        require(profile.statistics, "profile.statistics");
        require(profile.samples, "profile.samples");
        require(profile.syncContext, "profile.sync_context");
        require(profile.boundary, "profile.boundary");
        require(profile.overrides, "profile.overrides");
        require(profile.missingInformation, "profile.missing_information");
    }

    public void validate(AuditPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("audit_plan is required");
        }
        require(plan.tableClassification, "plan.table_classification");
        require(plan.semanticAnalysis, "plan.semantic_analysis");
        require(plan.riskAnalysis, "plan.risk_analysis");
        require(plan.recommendedSteps, "plan.recommended_steps");
        require(plan.dataAuditMapping, "plan.data_audit_mapping");
        require(plan.plannerTrace, "plan.planner_trace");
        require(plan.syncContext, "plan.sync_context");
        require(plan.deterministicBoundary, "plan.deterministic_boundary");
        require(plan.missingInformation, "plan.missing_information");
        if (isBlank(plan.tableClassification.tableType)) {
            throw new IllegalArgumentException("plan.table_classification.table_type is required");
        }
        if (isBlank(plan.tableClassification.scaleClass)) {
            throw new IllegalArgumentException("plan.table_classification.scale_class is required");
        }
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (isBlank(step.id) || isBlank(step.type)) {
                throw new IllegalArgumentException("recommended step id/type is required");
            }
            if (step.mapping == null || isBlank(step.mapping.status)) {
                throw new IllegalArgumentException("recommended step mapping.status is required: " + step.id);
            }
            if (!isSupportStatus(step.mapping.status)) {
                throw new IllegalArgumentException("recommended step mapping.status is invalid: " + step.id);
            }
            if (!isBlank(step.mapping.stepId) && !step.id.equals(step.mapping.stepId)) {
                throw new IllegalArgumentException("recommended step mapping.step_id must match step id: " + step.id);
            }
            if (isBlank(step.description) || isBlank(step.triggerCondition)) {
                throw new IllegalArgumentException("recommended step description/trigger_condition is required: " + step.id);
            }
        }
        if (plan.deterministicBoundary.aiConsistencyConclusion) {
            throw new IllegalArgumentException("AI plan must not declare deterministic consistency");
        }
    }

    public void validate(RootCauseAnalysis analysis) {
        if (analysis == null) {
            throw new IllegalArgumentException("root_cause_analysis is required");
        }
        require(analysis.possibleRootCauses, "analysis.possible_root_causes");
        require(analysis.retrievedCases, "analysis.retrieved_cases");
        require(analysis.recommendedChecks, "analysis.recommended_checks");
        require(analysis.missingInformation, "analysis.missing_information");
        if (isBlank(analysis.anomalySummary)) {
            throw new IllegalArgumentException("analysis.anomaly_summary is required");
        }
        if (isBlank(analysis.aiSafetyNotice)) {
            throw new IllegalArgumentException("analysis.ai_safety_notice is required");
        }
    }

    private void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isSupportStatus(String value) {
        return "SUPPORTED".equals(value) || "PARTIAL".equals(value) || "UNSUPPORTED".equals(value);
    }
}
