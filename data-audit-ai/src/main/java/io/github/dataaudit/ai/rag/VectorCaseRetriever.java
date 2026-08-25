// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VectorCaseRetriever implements RagRetriever {
    private final List<IndexedCase> cases;
    private final EmbeddingClient embeddingClient;

    public VectorCaseRetriever(List<HistoricalCase> cases, EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient == null ? new HashingEmbeddingClient() : embeddingClient;
        this.cases = new ArrayList<>();
        for (HistoricalCase historicalCase : cases == null ? List.<HistoricalCase>of() : cases) {
            String text = caseText(historicalCase);
            this.cases.add(new IndexedCase(historicalCase, this.embeddingClient.embed(text)));
        }
    }

    public static VectorCaseRetriever fromDirectory(Path directory, EmbeddingClient embeddingClient) throws Exception {
        return new VectorCaseRetriever(LocalCaseRetriever.fromDirectory(directory).cases(), embeddingClient);
    }

    @Override
    public List<HistoricalCase> retrieve(Map<String, Object> features, int limit) {
        double[] query = embeddingClient.embed(String.valueOf(features));
        return cases.stream()
                .map(indexed -> new ScoredCase(indexed.historicalCase, cosine(query, indexed.vector)))
                .filter(scored -> scored.score > 0.0d)
                .sorted(Comparator.comparingDouble((ScoredCase scored) -> scored.score).reversed())
                .limit(limit)
                .map(scored -> scored.historicalCase)
                .toList();
    }

    @Override
    public List<RootCauseAnalysis.RetrievedCase> retrieveSummaries(Map<String, Object> features, int limit) {
        double[] query = embeddingClient.embed(String.valueOf(features));
        return cases.stream()
                .map(indexed -> new ScoredCase(indexed.historicalCase, cosine(query, indexed.vector)))
                .filter(scored -> scored.score > 0.0d)
                .sorted(Comparator.comparingDouble((ScoredCase scored) -> scored.score).reversed())
                .limit(limit)
                .map(scored -> summary(scored))
                .toList();
    }

    public List<HistoricalCase> cases() {
        return cases.stream().map(indexed -> indexed.historicalCase).toList();
    }

    private RootCauseAnalysis.RetrievedCase summary(ScoredCase scored) {
        RootCauseAnalysis.RetrievedCase summary = new RootCauseAnalysis.RetrievedCase();
        summary.id = scored.historicalCase.id;
        summary.title = scored.historicalCase.title;
        summary.score = scored.score;
        summary.matchedEvidence.add("vector_similarity:" + String.format(Locale.ROOT, "%.4f", scored.score));
        summary.matchedEvidence.add("embedding_provider:" + embeddingClient.name());
        return summary;
    }

    static String caseText(HistoricalCase historicalCase) {
        if (historicalCase == null) {
            return "";
        }
        return String.join(" ",
                safe(historicalCase.id),
                safe(historicalCase.title),
                safe(historicalCase.sourceType),
                safe(historicalCase.targetType),
                safe(historicalCase.syncMode),
                safe(historicalCase.writeMode),
                String.join(" ", historicalCase.symptoms),
                String.join(" ", historicalCase.evidencePatterns),
                String.join(" ", historicalCase.likelyCauses),
                String.join(" ", historicalCase.recommendedChecks),
                String.join(" ", historicalCase.tags));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private double cosine(double[] left, double[] right) {
        double dot = 0.0d;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private record IndexedCase(HistoricalCase historicalCase, double[] vector) {
    }

    private record ScoredCase(HistoricalCase historicalCase, double score) {
    }
}
