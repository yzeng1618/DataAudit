package io.github.dataaudit.ai.provider;

public interface AiClient {
    <T> T generateJson(String operation, Object input, Class<T> responseType) throws Exception;

    String name();
}
