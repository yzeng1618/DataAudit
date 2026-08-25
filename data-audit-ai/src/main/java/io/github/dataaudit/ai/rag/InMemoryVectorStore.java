// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class InMemoryVectorStore implements VectorStore {
    private final EmbeddingClient embeddingClient;
    private final List<IndexedCase> cases = new ArrayList<>();

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient == null ? new HashingEmbeddingClient() : embeddingClient;
    }

    @Override
    public void index(List<HistoricalCase> cases) {
        this.cases.clear();
        for (HistoricalCase historicalCase : cases == null ? List.<HistoricalCase>of() : cases) {
            this.cases.add(new IndexedCase(historicalCase, embeddingClient.embed(VectorCaseRetriever.caseText(historicalCase))));
        }
    }

    @Override
    public List<VectorSearchResult> search(double[] query, int limit) {
        return cases.stream()
                .map(indexed -> new VectorSearchResult(
                        indexed.historicalCase,
                        cosine(query, indexed.vector),
                        List.of("vector_store:" + name(), "vector_similarity:" + String.format(Locale.ROOT, "%.4f",
                                cosine(query, indexed.vector)))))
                .filter(result -> result.score() > 0.0d)
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public String name() {
        return "local-memory";
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
}
