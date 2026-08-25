// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.analysis;

import io.github.dataaudit.ai.guardrail.RequiredFieldGuardrail;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.rag.RagRetriever;

import java.util.List;
import java.util.Map;

public class RootCauseAnalyzer {
    private final RagRetriever retriever;
    private final AnomalyExtractor extractor;
    private final RequiredFieldGuardrail guardrail;

    public RootCauseAnalyzer(RagRetriever retriever) {
        this(retriever, new AnomalyExtractor(), new RequiredFieldGuardrail());
    }

    public RootCauseAnalyzer(RagRetriever retriever, AnomalyExtractor extractor, RequiredFieldGuardrail guardrail) {
        this.retriever = retriever;
        this.extractor = extractor;
        this.guardrail = guardrail;
    }

    public RootCauseAnalysis analyze(AuditPlan plan, Map<String, Object> auditResult) {
        Map<String, Object> features = extractor.extract(plan, auditResult);
        List<HistoricalCase> cases = retriever.retrieve(features, 3);
        RootCauseAnalysis analysis = new RootCauseAnalysis();
        analysis.anomalySummary = buildSummary(features);
        analysis.retrievedCases.addAll(retriever.retrieveSummaries(features, 3));
        if (Boolean.TRUE.equals(features.get("embedding_dim_mismatch"))) {
            addCause(analysis,
                    "可能是 embedding 模型版本或向量维度不一致导致 checksum 不一致",
                    0.82,
                    List.of("日志或指标包含 embedding_dim", "确定性结果显示 checksum 异常"),
                    analysis.retrievedCases,
                    List.of("检查 embedding_dim 分布", "核对 embedding 模型版本", "对向量字段执行维度/null 率检查"),
                    List.of("源端和目标端 embedding 模型版本", "向量字段 schema 明细"));
        }
        if (Boolean.TRUE.equals(features.get("partition_diff"))
                && "overwrite".equalsIgnoreCase(String.valueOf(features.get("write_mode")))) {
            addCause(analysis,
                    "可能是目标端分区 overwrite 覆盖范围不完整",
                    0.84,
                    List.of("差异集中在分区:" + features.get("diff_partition"), "write_mode=overwrite"),
                    analysis.retrievedCases,
                    List.of("检查目标端 snapshot/commit 明细", "检查目标分区文件数量", "对异常分区执行 bucket diff"),
                    List.of("目标端 snapshot 文件列表", "sink commit 明细"));
        }
        if (Boolean.TRUE.equals(features.get("decimal_precision_signal"))
                || containsCase(cases, "decimal")) {
            addCause(analysis,
                    "可能是 decimal precision/scale 映射或 normalization 配置不一致导致 checksum 差异",
                    0.74,
                    List.of("检索案例或风险字段指向 decimal precision/scale", "确定性结果存在 checksum 或字段值差异信号"),
                    analysis.retrievedCases,
                    List.of("检查 normalize.decimal_scale", "对金额字段执行 SUM/MIN/MAX", "检查 scale/precision 分布"),
                    List.of("字段级 checksum", "source/target decimal precision 和 scale"));
        }
        if (Boolean.TRUE.equals(features.get("doris_stream_load_retry"))
                || containsCase(cases, "doris")) {
            addCause(analysis,
                    "可能是 Doris Stream Load redirect/retry 或 rejected rows 处理导致目标端写入不完整",
                    0.76,
                    List.of("日志或检索案例包含 redirect/retry/307/rejected 信号"),
                    analysis.retrievedCases,
                    List.of("检查 Stream Load label", "检查 rejected rows", "核对 FE/BE redirect 和 retry 日志"),
                    List.of("Doris Stream Load label 明细", "rejected row 文件或错误计数"));
        }
        if (Boolean.TRUE.equals(features.get("cdc_boundary_signal"))
                || containsCase(cases, "cdc")) {
            addCause(analysis,
                    "可能是 CDC 或增量边界漏读导致部分记录未进入目标端",
                    0.72,
                    List.of("日志、指标或检索案例包含 CDC/checkpoint/boundary 信号"),
                    analysis.retrievedCases,
                    List.of("检查 checkpoint", "核对 source_records/sink_records", "核对增量起止位点"),
                    List.of("checkpoint 状态", "source/sink records 指标", "增量边界配置"));
        }
        if (Boolean.TRUE.equals(features.get("checksum_mismatch")) && analysis.possibleRootCauses.isEmpty()) {
            addCause(analysis,
                    "可能是字段值、类型 normalization 或半结构化字段序列化差异导致 checksum 不一致",
                    0.68,
                    List.of("确定性结果显示 checksum mismatch"),
                    analysis.retrievedCases,
                    List.of("按字段分组 checksum", "检查 decimal/timestamp/json normalization", "抽取 bucket diff 样本"),
                    List.of("字段级 checksum", "normalization 配置"));
        }
        if (analysis.possibleRootCauses.isEmpty()) {
            addCause(analysis,
                    "可能存在同步边界、写入提交或目标端读取快照不一致",
                    0.55,
                    List.of("确定性核验结果不是 CONSISTENT"),
                    analysis.retrievedCases,
                    List.of("核对 source/sink records", "检查 checkpoint 和 commit 日志", "重跑最小分区核验"),
                    List.of("任务日志", "source/sink metrics", "目标端 commit 信息"));
        }
        for (HistoricalCase historicalCase : cases) {
            analysis.recommendedChecks.addAll(historicalCase.recommendedChecks);
        }
        for (RootCauseAnalysis.PossibleCause cause : analysis.possibleRootCauses) {
            analysis.recommendedChecks.addAll(cause.recommendedChecks);
            analysis.missingInformation.addAll(cause.missingInformation);
        }
        analysis.recommendedChecks = analysis.recommendedChecks.stream().distinct().toList();
        analysis.missingInformation = analysis.missingInformation.stream().distinct().toList();
        guardrail.validate(analysis);
        return analysis;
    }

    private boolean containsCase(List<HistoricalCase> cases, String token) {
        String lowerToken = token.toLowerCase();
        return cases.stream().anyMatch(historicalCase ->
                safe(historicalCase.id).contains(lowerToken)
                        || safe(historicalCase.title).contains(lowerToken)
                        || historicalCase.tags.stream().anyMatch(tag -> safe(tag).contains(lowerToken)));
    }

    private String buildSummary(Map<String, Object> features) {
        String status = String.valueOf(features.getOrDefault("status", "UNKNOWN"));
        Object partition = features.get("diff_partition");
        if (partition != null) {
            return "确定性核验状态为 " + status + "，异常集中在 " + partition + "。";
        }
        return "确定性核验状态为 " + status + "，需要结合日志、指标和历史案例继续分析。";
    }

    private void addCause(RootCauseAnalysis analysis, String hypothesis, double confidence, List<String> evidence,
                          List<RootCauseAnalysis.RetrievedCase> cases, List<String> checks, List<String> missing) {
        RootCauseAnalysis.PossibleCause cause = new RootCauseAnalysis.PossibleCause();
        cause.hypothesis = hypothesis;
        cause.confidence = confidence;
        cause.evidence.addAll(evidence);
        cause.retrievedCases.addAll(cases);
        cause.evidence.addAll(evidenceLinks(cases));
        cause.recommendedChecks.addAll(checks);
        cause.missingInformation.addAll(missing);
        analysis.possibleRootCauses.add(cause);
    }

    private List<String> evidenceLinks(List<RootCauseAnalysis.RetrievedCase> cases) {
        return cases.stream()
                .filter(item -> item.id != null && !item.id.isBlank())
                .map(item -> "retrieved_case:" + item.id + " matched_evidence="
                        + String.join("|", item.matchedEvidence))
                .toList();
    }

    private String safe(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
