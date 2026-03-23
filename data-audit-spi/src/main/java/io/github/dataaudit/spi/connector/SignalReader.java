package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;

import java.util.List;

public interface SignalReader {
    SummaryMetrics readSummary(ReadRequest request) throws Exception;

    List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) throws Exception;
}
