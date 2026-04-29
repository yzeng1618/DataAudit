package io.github.dataaudit.ai.qa;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.CopilotAnswer;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.report.DeterministicFactExtractor;

import java.util.Map;

public class CopilotQaService {
    public CopilotAnswer answer(AuditPlan plan,
                                Map<String, Object> result,
                                RootCauseAnalysis analysis,
                                String question) {
        CopilotAnswer answer = new CopilotAnswer();
        answer.question = question;
        String normalized = question == null ? "" : question.toLowerCase();
        String status = String.valueOf(DeterministicFactExtractor.value(result, "status", "UNKNOWN"));
        String proofMode = String.valueOf(DeterministicFactExtractor.value(result, "proof_mode", "UNKNOWN"));
        String scope = String.valueOf(DeterministicFactExtractor.value(result, "diff_partition", "未定位"));
        answer.deterministicFacts.add("status=" + status);
        answer.deterministicFacts.add("proof_mode=" + proofMode);
        answer.deterministicFacts.add("scope=" + scope);
        if (analysis != null) {
            analysis.possibleRootCauses.stream()
                    .limit(3)
                    .map(cause -> cause.hypothesis)
                    .forEach(answer.aiHypotheses::add);
            answer.recommendedChecks.addAll(analysis.recommendedChecks);
        }
        if (normalized.contains("accept") || normalized.contains("验收") || normalized.contains("block")) {
            answer.answer = acceptanceAnswer(status, proofMode, scope);
        } else if (normalized.contains("full") || normalized.contains("全表") || normalized.contains("exact diff")) {
            answer.answer = "是否需要全表 exact diff 取决于当前 proof mode、规模和异常范围。当前 proof_mode="
                    + proofMode + "，scope=" + scope + "。优先执行推荐的最小确定性检查，再决定是否扩大到全表。";
        } else if (normalized.contains("next") || normalized.contains("下一步") || normalized.contains("smallest")) {
            String next = answer.recommendedChecks.isEmpty()
                    ? "重跑最小范围 DataAudit diff 或补充 source/sink metrics。"
                    : answer.recommendedChecks.get(0);
            answer.answer = "下一步最小确定性检查建议是：" + next;
        } else if (normalized.contains("overwrite") || normalized.contains("覆盖")) {
            answer.answer = "overwrite 只是 AI 假设，不是确定性结论。当前应结合异常范围 " + scope
                    + " 检查目标端 snapshot/commit 和分区文件数量。";
        } else {
            answer.answer = "当前确定性状态为 " + status + "，proof_mode=" + proofMode
                    + "，异常范围=" + scope + "。AI 假设只能用于排序排查路径，不能替代 DataAudit 结论。";
        }
        return answer;
    }

    private String acceptanceAnswer(String status, String proofMode, String scope) {
        if ("DIFF_FOUND".equalsIgnoreCase(status)) {
            return "不建议验收通过。DataAudit 确定性结果为 DIFF_FOUND，proof_mode="
                    + proofMode + "，异常范围=" + scope + "。需要完成修复或补充复核后再验收。";
        }
        if ("CONSISTENT".equalsIgnoreCase(status)) {
            return "从 DataAudit 确定性结果看，当前不因一致性核验阻塞验收；仍需按项目准入规则检查其他非数据一致性条件。";
        }
        return "当前状态为 " + status + "，不能直接给出验收通过建议，需要先补充确定性证据。";
    }
}
