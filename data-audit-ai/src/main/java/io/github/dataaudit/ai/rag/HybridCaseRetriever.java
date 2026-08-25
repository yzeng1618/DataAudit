// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HybridCaseRetriever implements RagRetriever {
    private final LocalCaseRetriever lexicalRetriever;
    private final RagRetriever vectorRetriever;

    public HybridCaseRetriever(LocalCaseRetriever lexicalRetriever, RagRetriever vectorRetriever) {
        this.lexicalRetriever = lexicalRetriever == null ? new LocalCaseRetriever() : lexicalRetriever;
        this.vectorRetriever = vectorRetriever == null
                ? new VectorCaseRetriever(this.lexicalRetriever.cases(), new HashingEmbeddingClient())
                : vectorRetriever;
    }

    public static HybridCaseRetriever fromDirectory(Path directory, EmbeddingClient embeddingClient) throws Exception {
        LocalCaseRetriever lexical = LocalCaseRetriever.fromDirectory(directory);
        return new HybridCaseRetriever(lexical, new VectorCaseRetriever(lexical.cases(), embeddingClient));
    }

    @Override
    public List<HistoricalCase> retrieve(Map<String, Object> features, int limit) {
        return retrieveSummaries(features, limit).stream()
                .map(summary -> find(summary.id))
                .filter(item -> item != null)
                .toList();
    }

    @Override
    public List<RootCauseAnalysis.RetrievedCase> retrieveSummaries(Map<String, Object> features, int limit) {
        int expandedLimit = Math.max(limit * 3, 10);
        Map<String, RootCauseAnalysis.RetrievedCase> merged = new LinkedHashMap<>();
        for (RootCauseAnalysis.RetrievedCase summary : lexicalRetriever.retrieveSummaries(features, expandedLimit)) {
            RootCauseAnalysis.RetrievedCase target = merged.computeIfAbsent(summary.id, ignored -> copy(summary));
            target.score += summary.score * 0.6d;
            target.matchedEvidence.add("lexical_score:" + summary.score);
            target.matchedEvidence.addAll(summary.matchedEvidence);
        }
        for (RootCauseAnalysis.RetrievedCase summary : vectorRetriever.retrieveSummaries(features, expandedLimit)) {
            RootCauseAnalysis.RetrievedCase target = merged.computeIfAbsent(summary.id, ignored -> copy(summary));
            target.score += summary.score * 0.4d;
            target.matchedEvidence.add("vector_score:" + summary.score);
            target.matchedEvidence.addAll(summary.matchedEvidence);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((RootCauseAnalysis.RetrievedCase item) -> item.score).reversed())
                .limit(limit)
                .toList();
    }

    private RootCauseAnalysis.RetrievedCase copy(RootCauseAnalysis.RetrievedCase input) {
        RootCauseAnalysis.RetrievedCase copy = new RootCauseAnalysis.RetrievedCase();
        copy.id = input.id;
        copy.title = input.title;
        copy.score = 0.0d;
        copy.matchedEvidence = new ArrayList<>();
        return copy;
    }

    private HistoricalCase find(String id) {
        for (HistoricalCase historicalCase : lexicalRetriever.cases()) {
            if (id != null && id.equals(historicalCase.id)) {
                return historicalCase;
            }
        }
        return null;
    }
}
