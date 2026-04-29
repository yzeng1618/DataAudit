package io.github.dataaudit.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.guardrail.JsonSchemas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleAiClient implements AiClient {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiClient(URI baseUrlOrEndpoint, String apiKey, String model) {
        this(baseUrlOrEndpoint, apiKey, model, AiObjectMapper.create());
    }

    public OpenAiCompatibleAiClient(URI baseUrlOrEndpoint, String apiKey, String model, ObjectMapper mapper) {
        this.endpoint = chatCompletionsEndpoint(baseUrlOrEndpoint);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "mimo-v2.5-pro" : model;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public <T> T generateJson(String operation, Object input, Class<T> responseType) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0);
        requestBody.put("messages", messages(operation, input, responseType));

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI-compatible provider returned HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            throw new IllegalStateException("OpenAI-compatible provider response missing choices[0].message.content");
        }
        String content = stripJsonFence(contentNode.asText());
        return mapper.treeToValue(mapper.readTree(content), responseType);
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    private List<Map<String, String>> messages(String operation, Object input, Class<?> responseType) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "You are DataAudit AI Copilot. Return only one valid JSON object matching the requested Java response type. Never include markdown fences or explanatory prose."));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("response_type", responseType.getSimpleName());
        payload.put("response_schema", JsonSchemas.schemaFor(responseType));
        payload.put("input", input);
        messages.add(Map.of("role", "user", "content", mapper.writeValueAsString(payload)));
        return messages;
    }

    private URI chatCompletionsEndpoint(URI baseUrlOrEndpoint) {
        String value = baseUrlOrEndpoint.toString();
        if (value.endsWith("/chat/completions")) {
            return baseUrlOrEndpoint;
        }
        String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (trimmed.endsWith("/v1")) {
            return URI.create(trimmed + "/chat/completions");
        }
        return URI.create(trimmed + "/v1/chat/completions");
    }

    private String stripJsonFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }
}
