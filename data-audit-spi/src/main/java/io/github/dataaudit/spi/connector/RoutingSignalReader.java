package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceSignal;

import java.util.List;

public interface RoutingSignalReader {
    List<SliceSignal> readRoutingSignals(ReadRequest request) throws Exception;
}
