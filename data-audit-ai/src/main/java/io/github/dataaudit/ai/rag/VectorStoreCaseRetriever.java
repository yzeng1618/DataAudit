package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.util.List;
import java.util.Map;

public class VectorStoreCaseRetriever implements RagRetriever {
    private final VectorStore store;
    private final EmbeddingClient embeddingClient;

    public VectorStoreCaseRetriever(VectorStore store, EmbeddingClient embeddingClient) {
        this.store = store;
        this.embeddingClient = embeddingClient == null ? new HashingEmbeddingClient() : embeddingClient;
    }

    @Override
    public List<HistoricalCase> retrieve(Map<String, Object> features, int limit) {
        return store.search(embeddingClient.embed(String.valueOf(features)), limit).stream()
                .map(VectorSearchResult::historicalCase)
                .toList();
    }

    @Override
    public List<RootCauseAnalysis.RetrievedCase> retrieveSummaries(Map<String, Object> features, int limit) {
        return store.search(embeddingClient.embed(String.valueOf(features)), limit).stream()
                .map(this::summary)
                .toList();
    }

    private RootCauseAnalysis.RetrievedCase summary(VectorSearchResult result) {
        RootCauseAnalysis.RetrievedCase summary = new RootCauseAnalysis.RetrievedCase();
        summary.id = result.historicalCase().id;
        summary.title = result.historicalCase().title;
        summary.score = result.score();
        summary.matchedEvidence.addAll(result.matchedEvidence());
        summary.matchedEvidence.add("embedding_provider:" + embeddingClient.name());
        return summary;
    }
}
