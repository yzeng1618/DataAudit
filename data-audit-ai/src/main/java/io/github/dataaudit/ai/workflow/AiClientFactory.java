// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.workflow;

import io.github.dataaudit.ai.provider.AiClient;
import io.github.dataaudit.ai.provider.DisabledAiClient;
import io.github.dataaudit.ai.provider.HttpJsonAiClient;
import io.github.dataaudit.ai.provider.MockAiClient;
import io.github.dataaudit.ai.provider.OpenAiCompatibleAiClient;
import io.github.dataaudit.ai.provider.OpenAiSdkAiClient;

public class AiClientFactory {
    public AiClient create(AiWorkflowConfig config) {
        AiWorkflowConfig safeConfig = config == null ? new AiWorkflowConfig() : config;
        String provider = safeConfig.provider == null ? "disabled" : safeConfig.provider.toLowerCase();
        return switch (provider) {
            case "disabled" -> new DisabledAiClient();
            case "mock" -> new MockAiClient();
            case "http-json" -> {
                if (safeConfig.endpoint == null) {
                    throw new IllegalArgumentException("AI provider http-json requires --ai-endpoint or DATAAUDIT_AI_ENDPOINT");
                }
                yield new HttpJsonAiClient(safeConfig.endpoint, safeConfig.apiKey);
            }
            case "openai-compatible", "openai" -> {
                if (safeConfig.endpoint == null) {
                    throw new IllegalArgumentException("AI provider openai-compatible requires --ai-endpoint or DATAAUDIT_AI_ENDPOINT");
                }
                yield new OpenAiCompatibleAiClient(safeConfig.endpoint, safeConfig.apiKey, safeConfig.model);
            }
            case "openai-sdk", "openai-official" -> new OpenAiSdkAiClient(safeConfig.endpoint, safeConfig.apiKey, safeConfig.model);
            default -> throw new IllegalArgumentException("Unsupported AI provider: " + safeConfig.provider);
        };
    }
}
