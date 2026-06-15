package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;

import java.util.List;

public record VectorSearchResult(HistoricalCase historicalCase, double score, List<String> matchedEvidence) {
}
