package io.github.dataaudit.ai.workflow;

public final class AiPromptContracts {
    private AiPromptContracts() {
    }

    public static final String PLAN_AUDIT = "dataaudit.ai.plan.v1";
    public static final String ANALYZE_ROOT_CAUSE = "dataaudit.ai.explain.v1";
    public static final String REWRITE_REPORT = "dataaudit.ai.report.rewrite.v1";

    public static final String PLANNING_CONTRACT = """
            Produce an AuditPlan JSON proposal only. Recommend deterministic checks,
            include confidence/evidence/missing_information, and never conclude data consistency.
            """;

    public static final String ANALYSIS_CONTRACT = """
            Produce a RootCauseAnalysis JSON proposal only. Rank possible causes as hypotheses,
            cite deterministic evidence and retrieved cases, and never replace DataAudit status.
            """;

    public static final String REPORT_REWRITE_CONTRACT = """
            Rewrite Markdown for the selected audience without changing deterministic audit facts,
            proof mode, confidence, difference scope, safety notice, or hypothesis wording.
            """;
}
