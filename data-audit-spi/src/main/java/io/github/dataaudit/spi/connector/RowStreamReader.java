package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;

import java.util.Map;

public interface RowStreamReader {
    void scanRows(ReadRequest request, RowVisitor visitor) throws Exception;

    @FunctionalInterface
    interface RowVisitor {
        void accept(Map<String, Object> row) throws Exception;
    }
}
