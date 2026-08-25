// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.report;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.util.List;
import java.util.Map;

public class MarkdownReportGenerator implements AiReportGenerator {
    @Override
    public String render(AuditPlan plan, Map<String, Object> auditResult, RootCauseAnalysis analysis, String template) {
        return switch (template == null ? "technical" : template) {
            case "acceptance" -> acceptance(plan, auditResult, analysis);
            case "management" -> management(plan, auditResult, analysis);
            default -> technical(plan, auditResult, analysis);
        };
    }

    private String technical(AuditPlan plan, Map<String, Object> result, RootCauseAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("# DataAudit AI Copilot 技术排查版\n\n");
        appendDeterministicStatus(builder, result);
        builder.append("\n## 异常范围\n\n");
        builder.append("- 异常分区: ").append(value(result, "diff_partition", "未定位")).append("\n");
        builder.append("- proof_mode: ").append(value(result, "proof_mode", "UNKNOWN")).append("\n\n");
        builder.append("## SQL/DSL 排查建议\n\n");
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (step.sqlTemplate != null && !step.sqlTemplate.isBlank()) {
                builder.append("- `").append(step.type).append("`: `")
                        .append(step.sqlTemplate).append("`\n");
            }
        }
        builder.append("\n## 日志与指标证据\n\n");
        builder.append("- logs: ").append(value(result, "logs", "未提供")).append("\n");
        builder.append("- metrics: ").append(value(result, "metrics", "未提供")).append("\n\n");
        appendAnalysis(builder, analysis);
        appendRecommendedChecks(builder, analysis);
        builder.append("\n## 下一步 diff 命令\n\n");
        builder.append("```bash\n");
        builder.append("data-audit diff -f task.yaml --slice ${slice}\n");
        builder.append("```\n\n");
        appendQuestions(builder, List.of(
                "为什么不直接做全表 diff？",
                "下一步最小确定性检查是什么？",
                "应该优先检查哪个分区或 bucket？"));
        return builder.toString();
    }

    private String acceptance(AuditPlan plan, Map<String, Object> result, RootCauseAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("# DataAudit AI Copilot 验收交付版\n\n");
        appendDeterministicStatus(builder, result);
        String status = String.valueOf(value(result, "status", "UNKNOWN"));
        builder.append("\n## 差异范围与风险\n\n");
        builder.append("- 差异范围: ").append(value(result, "diff_partition", "未定位")).append("\n");
        builder.append("- proof_mode: ").append(value(result, "proof_mode", "UNKNOWN")).append("\n");
        builder.append("- 证明强度: ").append(value(result, "confidence", "UNKNOWN")).append("\n");
        builder.append("- 风险等级: ").append("DIFF_FOUND".equalsIgnoreCase(status) ? "高" : "待评估").append("\n");
        builder.append("- 验收建议: ").append("DIFF_FOUND".equalsIgnoreCase(status)
                ? "不建议验收通过，需完成确定性复核或修复后重跑"
                : "按确定性结果和项目准入规则处理").append("\n\n");
        appendAnalysis(builder, analysis);
        builder.append("\n## 下一步处理建议\n\n");
        builder.append("- 保留当前 DataAudit 报告作为确定性证据。\n");
        builder.append("- 按 recommended checks 复核异常范围。\n");
        builder.append("- 处理完成后重新运行 `data-audit check`。\n");
        return builder.toString();
    }

    private String management(AuditPlan plan, Map<String, Object> result, RootCauseAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("# DataAudit AI Copilot 管理摘要版\n\n");
        appendDeterministicStatus(builder, result);
        String status = String.valueOf(value(result, "status", "UNKNOWN"));
        builder.append("\n## 摘要\n\n");
        builder.append("- 影响范围: ").append(value(result, "diff_partition", "未定位")).append("\n");
        builder.append("- 当前风险: ").append("DIFF_FOUND".equalsIgnoreCase(status) ? "存在确定性差异" : "待观察").append("\n");
        builder.append("- 处理路径: 定位异常范围 -> 完成下一步确定性检查 -> 修复或重跑 -> 复验。\n");
        builder.append("- 阻塞状态: ").append("DIFF_FOUND".equalsIgnoreCase(status) ? "阻塞验收或上线" : "不明确").append("\n\n");
        appendAnalysis(builder, analysis);
        appendQuestions(builder, List.of(
                "当前是否阻塞验收？",
                "预计需要补充哪些证据？",
                "下一步处理路径是什么？"));
        return builder.toString();
    }

    private void appendDeterministicStatus(StringBuilder builder, Map<String, Object> result) {
        String status = String.valueOf(value(result, "status", "UNKNOWN"));
        builder.append("## 确定性核验状态\n\n");
        if ("CONSISTENT".equalsIgnoreCase(status)) {
            builder.append("- 状态: 确定性核验通过\n");
        } else if ("DIFF_FOUND".equalsIgnoreCase(status)) {
            builder.append("- 状态: 确定性核验发现差异\n");
        } else {
            builder.append("- 状态: ").append(status).append("\n");
        }
        builder.append("- 状态来源: DataAudit 确定性 audit result，AI 不覆盖该状态。\n");
    }

    private void appendAnalysis(StringBuilder builder, RootCauseAnalysis analysis) {
        builder.append("## AI 可能原因与证据链\n\n");
        if (analysis == null || analysis.possibleRootCauses.isEmpty()) {
            builder.append("- 未提供 root_cause_analysis.json。\n");
            return;
        }
        for (RootCauseAnalysis.PossibleCause cause : analysis.possibleRootCauses) {
            builder.append("- 假设: ").append(cause.hypothesis)
                    .append("，confidence=").append(String.format("%.2f", cause.confidence)).append("\n");
            builder.append("  - evidence: ").append(String.join("; ", cause.evidence)).append("\n");
            builder.append("  - recommended_checks: ")
                    .append(String.join("; ", cause.recommendedChecks)).append("\n");
            builder.append("  - missing_information: ")
                    .append(String.join("; ", cause.missingInformation)).append("\n");
        }
        if (!analysis.retrievedCases.isEmpty()) {
            builder.append("\n## 检索案例\n\n");
            for (RootCauseAnalysis.RetrievedCase retrievedCase : analysis.retrievedCases) {
                builder.append("- ").append(retrievedCase.id).append(": ").append(retrievedCase.title)
                        .append(" (score=").append(String.format("%.2f", retrievedCase.score)).append(")");
                if (!retrievedCase.matchedEvidence.isEmpty()) {
                    builder.append(", matched_evidence=")
                            .append(String.join("; ", retrievedCase.matchedEvidence));
                }
                builder.append("\n");
            }
        }
    }

    private void appendRecommendedChecks(StringBuilder builder, RootCauseAnalysis analysis) {
        builder.append("\n## 推荐确定性检查\n\n");
        if (analysis == null || analysis.recommendedChecks.isEmpty()) {
            builder.append("- 未提供 recommended checks。\n");
        } else {
            for (String check : analysis.recommendedChecks) {
                builder.append("- ").append(check).append("\n");
            }
        }
        builder.append("\n## 缺失信息\n\n");
        if (analysis == null || analysis.missingInformation.isEmpty()) {
            builder.append("- 未提供 missing information。\n");
        } else {
            for (String missing : analysis.missingInformation) {
                builder.append("- ").append(missing).append("\n");
            }
        }
    }

    private void appendQuestions(StringBuilder builder, List<String> questions) {
        builder.append("\n## 建议追问\n\n");
        for (String question : questions) {
            builder.append("- ").append(question).append("\n");
        }
    }

    private Object value(Map<String, Object> result, String key, Object fallback) {
        return DeterministicFactExtractor.value(result, key, fallback);
    }
}
