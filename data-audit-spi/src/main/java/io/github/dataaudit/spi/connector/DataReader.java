package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;

import java.util.List;
import java.util.Map;

public interface DataReader {
    List<Map<String, Object>> readRows(ReadRequest request) throws Exception;

    List<String> listSegmentValues(String column, ReadRequest request) throws Exception;
}

