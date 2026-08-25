// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LocalCaseRetriever implements RagRetriever {
    private final List<HistoricalCase> cases;

    public LocalCaseRetriever() {
        this(defaultCases());
    }

    public LocalCaseRetriever(List<HistoricalCase> cases) {
        this.cases = cases;
    }

    public static LocalCaseRetriever fromDirectory(Path directory) throws Exception {
        ObjectMapper mapper = AiObjectMapper.create();
        CorpusValidator validator = new CorpusValidator(mapper);
        List<HistoricalCase> loaded = new ArrayList<>(defaultCases());
        Set<String> ids = new LinkedHashSet<>();
        for (HistoricalCase historicalCase : loaded) {
            ids.add(historicalCase.id);
        }
        if (directory != null && Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".json")).toList()) {
                    HistoricalCase historicalCase = mapper.readValue(file.toFile(), HistoricalCase.class);
                    if (validator.valid(historicalCase) && ids.add(historicalCase.id)) {
                        loaded.add(historicalCase);
                    }
                }
            }
        }
        return new LocalCaseRetriever(loaded);
    }

    @Override
    public List<HistoricalCase> retrieve(Map<String, Object> features, int limit) {
        return cases.stream()
                .map(c -> new ScoredCase(c, score(c, features)))
                .filter(scored -> scored.score > 0.0d)
                .sorted(Comparator.comparingDouble((ScoredCase scored) -> scored.score).reversed())
                .limit(limit)
                .map(scored -> scored.historicalCase)
                .toList();
    }

    @Override
    public List<RootCauseAnalysis.RetrievedCase> retrieveSummaries(Map<String, Object> features, int limit) {
        List<HistoricalCase> retrieved = retrieve(features, limit);
        List<RootCauseAnalysis.RetrievedCase> summaries = new ArrayList<>();
        for (HistoricalCase historicalCase : retrieved) {
            RootCauseAnalysis.RetrievedCase summary = new RootCauseAnalysis.RetrievedCase();
            summary.id = historicalCase.id;
            summary.title = historicalCase.title;
            summary.score = score(historicalCase, features);
            summary.matchedEvidence.addAll(matchedEvidence(historicalCase, features));
            summaries.add(summary);
        }
        return summaries;
    }

    public List<HistoricalCase> cases() {
        return List.copyOf(cases);
    }

    private double score(HistoricalCase historicalCase, Map<String, Object> features) {
        Set<String> tokens = tokens(features);
        double score = 0.0d;
        for (String tag : historicalCase.tags) {
            if (tokens.contains(lower(tag))) {
                score += 2.0d;
            }
        }
        for (String symptom : historicalCase.symptoms) {
            if (containsAny(tokens, symptom)) {
                score += 1.5d;
            }
        }
        for (String pattern : historicalCase.evidencePatterns) {
            if (containsAny(tokens, pattern)) {
                score += 1.2d;
            }
        }
        if (historicalCase.writeMode != null && tokens.contains(lower(historicalCase.writeMode))) {
            score += 2.0d;
        }
        if (historicalCase.sourceType != null && tokens.contains(lower(historicalCase.sourceType))) {
            score += 0.5d;
        }
        if (historicalCase.targetType != null && tokens.contains(lower(historicalCase.targetType))) {
            score += 0.5d;
        }
        return score;
    }

    private List<String> matchedEvidence(HistoricalCase historicalCase, Map<String, Object> features) {
        Set<String> tokens = tokens(features);
        List<String> matches = new ArrayList<>();
        for (String tag : historicalCase.tags) {
            if (tokens.contains(lower(tag))) {
                matches.add("tag:" + tag);
            }
        }
        for (String pattern : historicalCase.evidencePatterns) {
            if (containsAny(tokens, pattern)) {
                matches.add("pattern:" + pattern);
            }
        }
        if (matches.isEmpty()) {
            matches.add("lexical_similarity");
        }
        return matches;
    }

    private boolean containsAny(Set<String> tokens, String phrase) {
        for (String token : phrase.split("[^A-Za-z0-9_]+")) {
            if (!token.isBlank() && tokens.contains(lower(token))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokens(Map<String, Object> features) {
        Set<String> tokens = new LinkedHashSet<>();
        for (Object value : features.values()) {
            collectTokens(tokens, String.valueOf(value));
        }
        return tokens;
    }

    private void collectTokens(Set<String> tokens, String value) {
        for (String token : value.split("[^A-Za-z0-9_]+")) {
            if (!token.isBlank()) {
                tokens.add(lower(token));
            }
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static List<HistoricalCase> defaultCases() {
        List<HistoricalCase> cases = new ArrayList<>();
        cases.add(caseOf(
                "oracle-decimal-drift",
                "Oracle decimal(20,0) 到 NUMBER/decimal 精度映射异常",
                "oracle",
                "jdbc",
                null,
                null,
                List.of("checksum mismatch", "decimal precision drift"),
                List.of("decimal", "precision", "scale", "checksum"),
                List.of("可能是 decimal precision/scale 映射或 normalization 配置不一致"),
                List.of("检查 normalize.decimal_scale", "对金额字段执行 SUM/MIN/MAX 和 scale 分布检查"),
                List.of("oracle", "decimal", "checksum", "precision")));
        cases.add(caseOf(
                "iceberg-partition-overwrite",
                "Iceberg overwrite 分区覆盖范围不完整导致目标端少数据",
                "jdbc",
                "iceberg",
                null,
                "overwrite",
                List.of("target row count lower", "partition diff", "overwrite partition"),
                List.of("overwrite", "partition", "target_count", "sink commit", "dt"),
                List.of("可能是 overwrite 分区覆盖范围不完整或提交快照缺失部分文件"),
                List.of("检查 Iceberg snapshot", "检查目标分区文件数量", "对异常分区执行 bucket diff"),
                List.of("iceberg", "overwrite", "partition", "row_count")));
        cases.add(caseOf(
                "flink-cdc-boundary-miss",
                "Flink CDC 增量边界漏读导致目标端少数据",
                "flink-cdc",
                "jdbc",
                "cdc",
                null,
                List.of("target row count lower", "checkpoint", "incremental boundary"),
                List.of("checkpoint", "cdc", "source_records", "sink_records", "boundary"),
                List.of("可能是 CDC 边界或 checkpoint 恢复导致部分增量未写入"),
                List.of("检查 checkpoint", "检查 source_records/sink_records", "核对增量起止位点"),
                List.of("flink", "cdc", "checkpoint", "boundary")));
        cases.add(caseOf(
                "doris-stream-load-redirect",
                "Doris Stream Load 307 redirect/retry 后部分记录未提交",
                "jdbc",
                "doris",
                null,
                null,
                List.of("target row count lower", "retry", "redirect"),
                List.of("307", "redirect", "retry", "stream load", "rejected"),
                List.of("可能是 Doris Stream Load 重定向、重试或 rejected rows 处理异常"),
                List.of("检查 Stream Load label", "检查 rejected rows", "核对 FE/BE redirect 日志"),
                List.of("doris", "stream", "redirect", "retry")));
        cases.add(caseOf(
                "embedding-dimension-mismatch",
                "RAG 数据集 embedding_dim 不一致导致向量字段 checksum 不一致",
                "jdbc",
                "vector-store",
                null,
                null,
                List.of("checksum mismatch", "embedding_dim mismatch"),
                List.of("embedding_dim", "vector", "1536", "1024", "schema drift"),
                List.of("可能是 embedding 模型版本或向量维度不一致"),
                List.of("检查 embedding_dim 分布", "检查 embedding 模型版本", "对向量字段执行维度和 null 率检查"),
                List.of("embedding", "vector", "checksum", "schema")));
        return cases;
    }

    private static HistoricalCase caseOf(String id, String title, String sourceType, String targetType,
                                         String syncMode, String writeMode, List<String> symptoms,
                                         List<String> evidencePatterns, List<String> likelyCauses,
                                         List<String> recommendedChecks, List<String> tags) {
        HistoricalCase historicalCase = new HistoricalCase();
        historicalCase.id = id;
        historicalCase.title = title;
        historicalCase.sourceType = sourceType;
        historicalCase.targetType = targetType;
        historicalCase.syncMode = syncMode;
        historicalCase.writeMode = writeMode;
        historicalCase.symptoms.addAll(symptoms);
        historicalCase.evidencePatterns.addAll(evidencePatterns);
        historicalCase.likelyCauses.addAll(likelyCauses);
        historicalCase.recommendedChecks.addAll(recommendedChecks);
        historicalCase.tags.addAll(tags);
        return historicalCase;
    }

    private record ScoredCase(HistoricalCase historicalCase, double score) {
    }
}
