package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalQualityFixtureTest {
    @Test
    void shouldRankProductionQualityCaseFamilies() throws Exception {
        HybridCaseRetriever retriever = HybridCaseRetriever.fromDirectory(
                Path.of("..", "examples", "ai-copilot", "cases"),
                new HashingEmbeddingClient());

        assertTopCase(retriever,
                Map.of("signal", "oracle decimal precision scale checksum amount drift"),
                "oracle-decimal-drift");
        assertTopCase(retriever,
                Map.of("signal", "iceberg overwrite partition dt target_count sink commit"),
                "iceberg-partition-overwrite");
        assertTopCase(retriever,
                Map.of("signal", "flink cdc checkpoint boundary source_records sink_records"),
                "flink-cdc-boundary-miss");
        assertTopCase(retriever,
                Map.of("signal", "doris stream load 307 redirect retry rejected rows"),
                "doris-stream-load-redirect");
    }

    private void assertTopCase(RagRetriever retriever, Map<String, Object> features, String expectedId) {
        List<HistoricalCase> retrieved = retriever.retrieve(features, 1);

        assertEquals(expectedId, retrieved.get(0).id);
    }
}
