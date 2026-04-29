package io.github.dataaudit.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.guardrail.JsonSchemas;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAiSdkAiClient implements AiClient {
    private final OpenAIClient client;
    private final String model;
    private final ObjectMapper mapper;

    public OpenAiSdkAiClient(URI baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, AiObjectMapper.create());
    }

    public OpenAiSdkAiClient(URI baseUrl, String apiKey, String model, ObjectMapper mapper) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder();
        if (apiKey == null || apiKey.isBlank()) {
            builder.fromEnv();
        } else {
            builder.apiKey(apiKey);
        }
        if (baseUrl != null) {
            builder.baseUrl(baseUrl.toString());
        }
        this.client = builder.build();
        this.model = model == null || model.isBlank() ? "gpt-5.2" : model;
        this.mapper = mapper;
    }

    @Override
    public <T> T generateJson(String operation, Object input, Class<T> responseType) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("response_type", responseType.getSimpleName());
        payload.put("response_schema", JsonSchemas.schemaFor(responseType));
        payload.put("input", input);
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .addSystemMessage("You are DataAudit AI Copilot. Return only one valid JSON object matching the response_schema. Never include markdown fences or explanatory prose.")
                .addUserMessage(mapper.writeValueAsString(payload))
                .build();
        ChatCompletion completion = client.chat().completions().create(params);
        String content = completion.choices().stream()
                .flatMap(choice -> choice.message().content().stream())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("OpenAI SDK provider response missing message content"));
        return mapper.treeToValue(mapper.readTree(stripJsonFence(content)), responseType);
    }

    @Override
    public String name() {
        return "openai-sdk";
    }

    private String stripJsonFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }
}
