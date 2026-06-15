package io.github.dataaudit.ai.rag;

import java.util.Locale;

public class VectorStoreFactory {
    public VectorStore create(String backend, EmbeddingClient embeddingClient) {
        String normalized = backend == null || backend.isBlank()
                ? "local-memory"
                : backend.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local", "local-memory", "in-memory" -> new InMemoryVectorStore(embeddingClient);
            default -> throw new IllegalArgumentException(
                    "Unsupported vector store backend: " + backend + ". Add an adapter behind VectorStore first.");
        };
    }
}
