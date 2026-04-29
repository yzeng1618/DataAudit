package io.github.dataaudit.ai.provider;

import java.util.HashMap;
import java.util.Map;

public class MockAiClient implements AiClient {
    private final Map<Class<?>, Object> responses = new HashMap<>();

    public <T> MockAiClient register(Class<T> type, T response) {
        responses.put(type, response);
        return this;
    }

    @Override
    public <T> T generateJson(String operation, Object input, Class<T> responseType) throws Exception {
        Object response = responses.get(responseType);
        if (response == null) {
            return responseType.getDeclaredConstructor().newInstance();
        }
        return responseType.cast(response);
    }

    @Override
    public String name() {
        return "mock";
    }
}
