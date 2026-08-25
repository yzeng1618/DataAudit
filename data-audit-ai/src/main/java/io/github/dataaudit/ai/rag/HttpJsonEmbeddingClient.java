// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class HttpJsonEmbeddingClient implements EmbeddingClient {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpJsonEmbeddingClient(URI endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, HttpClient.newHttpClient(), AiObjectMapper.create());
    }

    HttpJsonEmbeddingClient(URI endpoint, String apiKey, String model, HttpClient client, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public double[] embed(String text) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "input", text == null ? "" : text,
                    "model", model == null || model.isBlank() ? "default" : model));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Embedding provider returned HTTP " + response.statusCode());
            }
            Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {
            });
            return parseEmbedding(body);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding provider failed", e);
        }
    }

    @Override
    public String name() {
        return "http-json";
    }

    private double[] parseEmbedding(Map<String, Object> body) {
        Object direct = body.get("embedding");
        if (direct instanceof List<?> list) {
            return toVector(list);
        }
        Object data = body.get("data");
        if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object nested = first.get("embedding");
            if (nested instanceof List<?> embedding) {
                return toVector(embedding);
            }
        }
        throw new IllegalStateException("Embedding provider response missing embedding array");
    }

    private double[] toVector(List<?> values) {
        double[] vector = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Embedding value is not numeric at index " + i);
            }
            vector[i] = number.doubleValue();
        }
        return vector;
    }
}
