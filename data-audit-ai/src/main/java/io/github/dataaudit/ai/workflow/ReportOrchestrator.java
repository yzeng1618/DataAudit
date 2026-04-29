package io.github.dataaudit.ai.workflow;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.MarkdownReportRewrite;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.provider.AiClient;
import io.github.dataaudit.ai.report.DeterministicFactExtractor;
import io.github.dataaudit.ai.report.MarkdownReportGenerator;

import java.util.Map;

public class ReportOrchestrator {
    private final AiWorkflowConfig config;
    private final AiClient client;
    private final MarkdownReportGenerator fallbackGenerator;

    public ReportOrchestrator(AiWorkflowConfig config) {
        this(config, new AiClientFactory().create(config), new MarkdownReportGenerator());
    }

    public ReportOrchestrator(AiWorkflowConfig config, AiClient client, MarkdownReportGenerator fallbackGenerator) {
        this.config = config == null ? new AiWorkflowConfig() : config;
        this.client = client;
        this.fallbackGenerator = fallbackGenerator;
    }

    public String render(AuditPlan plan, Map<String, Object> result, RootCauseAnalysis analysis, String template) {
        String base = fallbackGenerator.render(plan, result, analysis, template);
        if (!config.providerEnabled()) {
            return base;
        }
        try {
            ReportRewriteRequest request = new ReportRewriteRequest(plan, result, analysis, template, base,
                    AiPromptContracts.REPORT_REWRITE_CONTRACT);
            MarkdownReportRewrite rewrite = client.generateJson(AiPromptContracts.REWRITE_REPORT,
                    request,
                    MarkdownReportRewrite.class);
            if (rewrite == null || rewrite.markdown == null || rewrite.markdown.isBlank()) {
                return base;
            }
            return preservesLockedFacts(base, rewrite.markdown, result) ? rewrite.markdown : base;
        } catch (Exception e) {
            if (!config.fallbackOnProviderError) {
                throw new IllegalStateException("AI report rewrite provider failed", e);
            }
            return base;
        }
    }

    private boolean preservesLockedFacts(String base, String rewritten, Map<String, Object> result) {
        for (String value : DeterministicFactExtractor.lockedFacts(result).values()) {
            if (base.contains(value) && !rewritten.contains(value)) {
                return false;
            }
        }
        return true;
    }

    public record ReportRewriteRequest(AuditPlan plan,
                                       Map<String, Object> auditResult,
                                       RootCauseAnalysis analysis,
                                       String template,
                                       String baseMarkdown,
                                       String contract) {
    }
}
