package io.github.dataaudit.ai.report;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.util.Map;

public interface AiReportGenerator {
    String render(AuditPlan plan, Map<String, Object> auditResult, RootCauseAnalysis analysis, String template);
}
