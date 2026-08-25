package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;

import java.util.List;

/**
 * Aggregate-signal access used for the cheap layers of an audit: global
 * summaries and per-slice rollups. Connectors that advertise
 * {@code supportsSignalPushdown} evaluate these as queries on the remote
 * system instead of streaming rows.
 */
public interface SignalReader {

    /**
     * Computes summary metrics (row count, checksum, per-column null/min/max/
     * distinct where supported) over the scope described by the request —
     * either the whole object or one slice/bucket/sample subset.
     *
     * @throws Exception on read failure; reported as an execution failure, not
     *                   as a data difference
     */
    SummaryMetrics readSummary(ReadRequest request) throws Exception;

    /**
     * Computes one {@link SliceSignal} per distinct value of
     * {@code sliceColumn} (typically a partition column), letting the engine
     * localize a mismatch before running an exact diff. Requires
     * {@code supportsGroupedSignalPushdown}.
     *
     * @throws Exception on read failure
     */
    List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) throws Exception;
}
