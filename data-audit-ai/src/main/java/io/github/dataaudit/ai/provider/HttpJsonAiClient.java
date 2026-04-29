package io.github.dataaudit.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.guardrail.JsonSchemas;
import io.github.dataaudit.ai.guardrail.RequiredFieldGuardrail;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpJsonAiClient implements AiClient {
    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final RequiredFieldGuardrail guardrail;
    private final HttpClient httpClient;

    public HttpJsonAiClient(URI endpoint, String apiKey) {
        this(endpoint, apiKey, AiObjectMapper.create(), new RequiredFieldGuardrail());
    }

    public HttpJsonAiClient(URI endpoint, String apiKey, ObjectMapper mapper, RequiredFieldGuardrail guardrail) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.guardrail = guardrail;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public <T> T generateJson(String operation, Object input, Class<T> responseType) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("operation", operation);
        requestBody.put("input", input);
        requestBody.put("response_type", responseType.getSimpleName());
        requestBody.put("response_schema", JsonSchemas.schemaFor(responseType));
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM provider returned HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode payload = root.has("output") ? root.get("output") : root;
        T value = mapper.treeToValue(payload, responseType);
        if (value instanceof AuditPlan auditPlan) {
            guardrail.validate(auditPlan);
        }
        if (value instanceof RootCauseAnalysis analysis) {
            guardrail.validate(analysis);
        }
        return value;
    }

    @Override
    public String name() {
        return "http-json";
    }
}
