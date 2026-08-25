// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.provider;

public class DisabledAiClient implements AiClient {
    @Override
    public <T> T generateJson(String operation, Object input, Class<T> responseType) {
        throw new UnsupportedOperationException("AI provider is disabled; use rule-based planner and local RAG fallback");
    }

    @Override
    public String name() {
        return "disabled";
    }
}
