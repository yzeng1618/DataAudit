// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionRagBoundaryTest {
    @Test
    void defaultEmbeddingConfigurationUsesOfflineHashingFallback() {
        EmbeddingClient client = new EmbeddingClientFactory().create(new EmbeddingProviderConfig());

        assertEquals("local-hashing", client.name());
        assertEquals(128, client.embed("checksum mismatch").length);
    }

    @Test
    void externalEmbeddingFailureFallsBackUnlessFailFastIsRequested() {
        EmbeddingProviderConfig config = new EmbeddingProviderConfig();
        config.provider = "failing-test";
        config.fallbackOnProviderError = true;

        EmbeddingClient client = new EmbeddingClientFactory(provider -> new FailingEmbeddingClient()).create(config);

        assertEquals("fallback(local-hashing<-failing-test)", client.name());
        assertEquals(128, client.embed("any text").length);
    }

    @Test
    void vectorStoreBoundaryRetrievesThroughProviderNeutralStore() {
        HistoricalCase historicalCase = new HistoricalCase();
        historicalCase.id = "vector-store-case";
        historicalCase.title = "Vector store case";
        historicalCase.symptoms.add("checksum mismatch");
        historicalCase.evidencePatterns.add("checksum");
        historicalCase.likelyCauses.add("可能是 checksum 差异");
        historicalCase.recommendedChecks.add("检查 checksum");
        historicalCase.tags.add("checksum");

        VectorStore store = new InMemoryVectorStore(new HashingEmbeddingClient());
        store.index(List.of(historicalCase));
        VectorStoreCaseRetriever retriever = new VectorStoreCaseRetriever(store, new HashingEmbeddingClient());

        List<HistoricalCase> retrieved = retriever.retrieve(Map.of("signal", "checksum mismatch"), 3);

        assertFalse(retrieved.isEmpty());
        assertEquals("vector-store-case", retrieved.get(0).id);
    }

    private static class FailingEmbeddingClient implements EmbeddingClient {
        @Override
        public double[] embed(String text) {
            throw new IllegalStateException("external embedding unavailable");
        }

        @Override
        public String name() {
            return "failing-test";
        }
    }
}
