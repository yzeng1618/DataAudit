// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.guardrail;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

public class RequiredFieldGuardrail {
    public void validate(AuditPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("audit plan is required");
        }
        if (plan.tableClassification.confidence <= 0.0d || plan.tableClassification.confidence > 1.0d
                || plan.tableClassification.evidence.isEmpty()
                || plan.tableClassification.missingInformation.isEmpty()) {
            throw new IllegalArgumentException("table classification requires confidence/evidence/missing_information");
        }
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (step.confidence <= 0.0d || step.confidence > 1.0d) {
                throw new IllegalArgumentException("recommended step confidence is required: " + step.id);
            }
            if (step.evidence == null || step.evidence.isEmpty()) {
                throw new IllegalArgumentException("recommended step evidence is required: " + step.id);
            }
            if (step.missingInformation == null || step.missingInformation.isEmpty()) {
                throw new IllegalArgumentException("recommended step missing_information is required: " + step.id);
            }
        }
        for (AuditPlan.RiskItem risk : plan.riskAnalysis) {
            if (risk.confidence <= 0.0d || risk.confidence > 1.0d
                    || risk.evidence.isEmpty() || risk.missingInformation.isEmpty()) {
                throw new IllegalArgumentException("risk analysis requires confidence/evidence/missing_information");
            }
        }
    }

    public void validate(RootCauseAnalysis analysis) {
        if (analysis == null) {
            throw new IllegalArgumentException("root cause analysis is required");
        }
        for (RootCauseAnalysis.PossibleCause cause : analysis.possibleRootCauses) {
            if (cause.confidence <= 0.0d || cause.confidence > 1.0d || cause.evidence.isEmpty()
                    || cause.recommendedChecks.isEmpty() || cause.missingInformation.isEmpty()) {
                throw new IllegalArgumentException("possible cause requires confidence/evidence/checks/missing_information");
            }
        }
        if (analysis.aiSafetyNotice == null || analysis.aiSafetyNotice.isBlank()) {
            throw new IllegalArgumentException("ai_safety_notice is required");
        }
    }
}
