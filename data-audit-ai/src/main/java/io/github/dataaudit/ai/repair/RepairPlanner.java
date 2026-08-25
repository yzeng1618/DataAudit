// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.repair;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RepairPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.report.DeterministicFactExtractor;

import java.util.Locale;
import java.util.Map;

public class RepairPlanner {
    public RepairPlan plan(AuditPlan plan, Map<String, Object> result, RootCauseAnalysis analysis) {
        RepairPlan repairPlan = new RepairPlan();
        repairPlan.deterministicStatus = String.valueOf(DeterministicFactExtractor.value(result, "status", "UNKNOWN"));
        addConfigRepairs(plan, analysis, repairPlan);
        addDataHandlingRepairs(result, analysis, repairPlan);
        if (repairPlan.actions.isEmpty()) {
            repairPlan.actions.add(action(
                    "collect_more_evidence",
                    "manual_check",
                    "补充确定性证据后再制定修复动作",
                    "medium",
                    false,
                    null,
                    null,
                    null,
                    "no specific repair action could be safely inferred"));
        }
        repairPlan.missingInformation = repairPlan.actions.stream()
                .flatMap(action -> action.nextChecks.stream())
                .distinct()
                .toList();
        return repairPlan;
    }

    private void addConfigRepairs(AuditPlan plan, RootCauseAnalysis analysis, RepairPlan repairPlan) {
        String text = text(plan, analysis);
        if (containsAny(text, "timezone", "normalize.timezone", "时区")) {
            repairPlan.actions.add(action(
                    "set_timezone",
                    "config_patch",
                    "在 task.yaml 中明确 normalize.timezone，避免时间边界和分区偏移歧义",
                    "low",
                    true,
                    "normalize.timezone",
                    "UTC",
                    null,
                    "missing timezone or timezone risk evidence"));
        }
        if (containsAny(text, "write_mode", "overwrite", "append", "merge", "写入模式")) {
            repairPlan.actions.add(action(
                    "set_write_mode",
                    "config_patch",
                    "在 semantics.ai.write_mode 中声明写入模式，便于 planner 和 root-cause analyzer 排序风险",
                    "low",
                    true,
                    "semantics.ai.write_mode",
                    text.contains("overwrite") ? "overwrite" : "batch",
                    null,
                    "write mode evidence or missing information"));
        }
        if (containsAny(text, "sync_mode", "cdc", "incremental", "checkpoint", "同步模式")) {
            repairPlan.actions.add(action(
                    "set_sync_mode",
                    "config_patch",
                    "在 semantics.ai.sync_mode 中声明同步模式，便于 CDC/incremental 边界分析",
                    "low",
                    true,
                    "semantics.ai.sync_mode",
                    text.contains("cdc") ? "cdc" : "batch",
                    null,
                    "sync mode or boundary evidence"));
        }
    }

    private void addDataHandlingRepairs(Map<String, Object> result,
                                        RootCauseAnalysis analysis,
                                        RepairPlan repairPlan) {
        String status = String.valueOf(DeterministicFactExtractor.value(result, "status", "UNKNOWN"));
        String scope = String.valueOf(DeterministicFactExtractor.value(result, "diff_partition", "full_table"));
        String text = text(null, analysis);
        if ("DIFF_FOUND".equalsIgnoreCase(status)) {
            RepairPlan.RepairAction action = action(
                    "rerun_suspect_scope",
                    "rerun_command",
                    "对异常范围重新执行上游同步或最小范围复核，完成后重新运行 DataAudit check",
                    "medium",
                    false,
                    null,
                    null,
                    "data-audit diff -f task.yaml --slice " + scope,
                    "deterministic status is DIFF_FOUND");
            action.nextChecks.add("确认 rerun 前后的 source/sink records、commit 或 snapshot 明细");
            repairPlan.actions.add(action);
        }
        if (containsAny(text, "overwrite", "snapshot", "commit")) {
            RepairPlan.RepairAction action = action(
                    "inspect_overwrite_commit",
                    "manual_data_fix",
                    "检查 overwrite 覆盖范围、目标端 snapshot/commit 文件和异常分区文件数量",
                    "high",
                    false,
                    null,
                    null,
                    null,
                    "overwrite/snapshot hypothesis");
            action.nextChecks.add("检查目标端 snapshot manifest 或 commit log");
            action.nextChecks.add("对异常分区执行 bucket diff");
            repairPlan.actions.add(action);
        }
    }

    private RepairPlan.RepairAction action(String id,
                                           String type,
                                           String description,
                                           String riskLevel,
                                           boolean autoExecutable,
                                           String configPath,
                                           String suggestedValue,
                                           String command,
                                           String evidence) {
        RepairPlan.RepairAction action = new RepairPlan.RepairAction();
        action.id = id;
        action.type = type;
        action.description = description;
        action.riskLevel = riskLevel;
        action.autoExecutable = autoExecutable;
        action.configPath = configPath;
        action.suggestedValue = suggestedValue;
        action.command = command;
        action.evidence.add(evidence);
        if (configPath != null) {
            action.nextChecks.add("review patched task YAML before rerun");
        }
        return action;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String text(AuditPlan plan, RootCauseAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        if (plan != null) {
            builder.append(plan.missingInformation).append(' ');
            builder.append(plan.syncContext.writeMode).append(' ');
            builder.append(plan.syncContext.syncMode).append(' ');
            for (AuditPlan.RiskItem risk : plan.riskAnalysis) {
                builder.append(risk.riskType).append(' ')
                        .append(risk.description).append(' ')
                        .append(risk.evidence).append(' ')
                        .append(risk.missingInformation).append(' ');
            }
        }
        if (analysis != null) {
            builder.append(analysis.anomalySummary).append(' ')
                    .append(analysis.recommendedChecks).append(' ')
                    .append(analysis.missingInformation).append(' ');
            for (RootCauseAnalysis.PossibleCause cause : analysis.possibleRootCauses) {
                builder.append(cause.hypothesis).append(' ')
                        .append(cause.evidence).append(' ')
                        .append(cause.recommendedChecks).append(' ')
                        .append(cause.missingInformation).append(' ');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
