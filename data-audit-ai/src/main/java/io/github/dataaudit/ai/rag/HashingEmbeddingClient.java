package io.github.dataaudit.ai.rag;

import java.util.Locale;

public class HashingEmbeddingClient implements EmbeddingClient {
    private final int dimensions;

    public HashingEmbeddingClient() {
        this(128);
    }

    public HashingEmbeddingClient(int dimensions) {
        this.dimensions = Math.max(16, dimensions);
    }

    @Override
    public double[] embed(String text) {
        double[] vector = new double[dimensions];
        String safeText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : safeText.split("[^a-z0-9_]+")) {
            if (token.isBlank()) {
                continue;
            }
            int hash = token.hashCode();
            int index = Math.floorMod(hash, dimensions);
            double sign = (hash & 1) == 0 ? 1.0d : -1.0d;
            vector[index] += sign;
        }
        normalize(vector);
        return vector;
    }

    @Override
    public String name() {
        return "local-hashing";
    }

    private void normalize(double[] vector) {
        double sum = 0.0d;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0.0d) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
