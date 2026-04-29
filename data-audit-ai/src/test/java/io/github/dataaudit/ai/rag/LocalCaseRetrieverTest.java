package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCaseRetrieverTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldLoadDirectoryCasesAndDeduplicateById() throws Exception {
        Files.writeString(tempDir.resolve("custom.json"), """
                {
                  "id": "custom-boundary-case",
                  "title": "Custom incremental boundary case",
                  "sync_mode": "incremental",
                  "symptoms": ["boundary miss"],
                  "evidence_patterns": ["boundary"],
                  "likely_causes": ["incremental boundary missed records"],
                  "recommended_checks": ["check custom boundary"],
                  "tags": ["custom", "boundary"]
                }
                """);
        Files.writeString(tempDir.resolve("duplicate.json"), """
                {
                  "id": "flink-cdc-boundary-miss",
                  "title": "Duplicate should not override built-in case",
                  "tags": ["boundary"]
                }
                """);

        LocalCaseRetriever retriever = LocalCaseRetriever.fromDirectory(tempDir);
        List<HistoricalCase> cases = retriever.retrieve(Map.of("sync_mode", "incremental boundary custom"), 10);

        assertTrue(cases.stream().anyMatch(item -> "custom-boundary-case".equals(item.id)));
        assertEquals(1, cases.stream().filter(item -> "flink-cdc-boundary-miss".equals(item.id)).count());
    }

    @Test
    void shouldRetrieveCasesWithVectorAndHybridModes() throws Exception {
        VectorCaseRetriever vector = VectorCaseRetriever.fromDirectory(null, new HashingEmbeddingClient());
        HybridCaseRetriever hybrid = HybridCaseRetriever.fromDirectory(null, new HashingEmbeddingClient());

        List<HistoricalCase> vectorCases = vector.retrieve(Map.of(
                "symptom", "embedding_dim vector checksum mismatch",
                "field", "embedding_vector"), 3);
        List<HistoricalCase> hybridCases = hybrid.retrieve(Map.of(
                "symptom", "embedding_dim vector checksum mismatch",
                "field", "embedding_vector"), 3);

        assertTrue(vectorCases.stream().anyMatch(item -> "embedding-dimension-mismatch".equals(item.id)));
        assertTrue(hybridCases.stream().anyMatch(item -> "embedding-dimension-mismatch".equals(item.id)));
    }
}
