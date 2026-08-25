// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import java.util.Locale;
import java.util.function.Function;

public class EmbeddingClientFactory {
    private final Function<String, EmbeddingClient> externalResolver;

    public EmbeddingClientFactory() {
        this(null);
    }

    public EmbeddingClientFactory(Function<String, EmbeddingClient> externalResolver) {
        this.externalResolver = externalResolver;
    }

    public EmbeddingClient create(EmbeddingProviderConfig config) {
        EmbeddingProviderConfig safeConfig = config == null ? new EmbeddingProviderConfig() : config;
        String provider = safeConfig.provider == null || safeConfig.provider.isBlank()
                ? "local-hashing"
                : safeConfig.provider.toLowerCase(Locale.ROOT);
        HashingEmbeddingClient fallback = new HashingEmbeddingClient(safeConfig.dimensions);
        if ("disabled".equals(provider) || "local".equals(provider) || "local-hashing".equals(provider)
                || "hashing".equals(provider)) {
            return fallback;
        }
        EmbeddingClient primary = external(provider, safeConfig);
        if (safeConfig.fallbackOnProviderError) {
            return new FallbackEmbeddingClient(primary, fallback);
        }
        return primary;
    }

    private EmbeddingClient external(String provider, EmbeddingProviderConfig config) {
        if (externalResolver != null) {
            return externalResolver.apply(provider);
        }
        if ("http-json".equals(provider)) {
            if (config.endpoint == null) {
                throw new IllegalArgumentException("Embedding provider http-json requires endpoint");
            }
            return new HttpJsonEmbeddingClient(config.endpoint, config.apiKey, config.model);
        }
        throw new IllegalArgumentException("Unsupported embedding provider: " + provider);
    }

    private record FallbackEmbeddingClient(EmbeddingClient primary, EmbeddingClient fallback) implements EmbeddingClient {
        @Override
        public double[] embed(String text) {
            try {
                return primary.embed(text);
            } catch (RuntimeException e) {
                return fallback.embed(text);
            }
        }

        @Override
        public String name() {
            return "fallback(" + fallback.name() + "<-" + primary.name() + ")";
        }
    }
}
