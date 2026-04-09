package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.RoutingSignalReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.LocalizationEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizationEngineTest {
    @Test
    void shouldPreferPartitionWindowSignalsForLargeTable() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.partitionBy.add("dt");
        spec.object.estimatedRows = 1_000_000L;

        LocalizationEvidence evidence = new LocalizationEngine().localize(
                spec,
                ScaleClass.LARGE,
                partitionedSignalReader("dt=2026-03-10", 10L, "10:1"),
                partitionedSignalReader("dt=2026-03-10", 11L, "11:1"),
                null,
                null,
                emptyRowReader(),
                emptyRowReader()
        );

        assertEquals("partition_window", evidence.strategy);
        assertEquals(ProofMode.GROUPED_CHECKSUM, evidence.proofMode);
        assertEquals("dt=2026-03-10", evidence.suspiciousScopes.get(0).sliceKey);
    }

    @Test
    void shouldFallbackToNoKeyXorForLargeTableWithoutGroupingAndKey() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 1_000_000L;

        LocalizationEvidence evidence = new LocalizationEngine().localize(
                spec,
                ScaleClass.LARGE,
                emptySignalReader(),
                emptySignalReader(),
                null,
                null,
                rowReader(row("id", 1, "value", "A")),
                rowReader(row("id", 1, "value", "B"))
        );

        assertTrue(evidence.noKeyMode);
        assertEquals(ProofMode.XOR_CHECKSUM_PLUS_SAMPLE, evidence.proofMode);
        assertEquals("no_key_xor_fallback", evidence.fallbackReason);
    }

    @Test
    void shouldUseRoutingDigestForXLargeWhenRoutingSignalsExist() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.columns.add("id");
        spec.object.columns.add("shard");
        spec.object.routingStrategy = "shard";
        spec.object.estimatedRows = 200_000_000L;

        LocalizationEvidence evidence = new LocalizationEngine().localize(
                spec,
                ScaleClass.XLARGE,
                emptySignalReader(),
                emptySignalReader(),
                routingSignalReader("routing=shard-01", 100L, "100:1"),
                routingSignalReader("routing=shard-01", 120L, "120:1"),
                emptyRowReader(),
                emptyRowReader()
        );

        assertEquals("routing_digest", evidence.strategy);
        assertEquals(ProofMode.ROUTING_DIGEST, evidence.proofMode);
        assertEquals("routing=shard-01", evidence.suspiciousScopes.get(0).sliceKey);
    }

    @Test
    void shouldFallbackToSamplingForXLargeWithoutStableSplitAndKey() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 200_000_000L;

        LocalizationEvidence evidence = new LocalizationEngine().localize(
                spec,
                ScaleClass.XLARGE,
                emptySignalReader(),
                emptySignalReader(),
                null,
                null,
                rowReader(row("id", 1, "value", "A")),
                rowReader(row("id", 1, "value", "B"))
        );

        assertTrue(evidence.noKeyMode);
        assertEquals(ProofMode.SAMPLING, evidence.proofMode);
        assertEquals(ConfidenceLevel.LOW, evidence.confidence);
        assertEquals("xlarge_sampling_fallback", evidence.fallbackReason);
    }

    private SignalReader partitionedSignalReader(String sliceKey, long rowCount, String checksum) {
        return new SignalReader() {
            @Override
            public SummaryMetrics readSummary(ReadRequest request) {
                SummaryMetrics metrics = new SummaryMetrics();
                metrics.rowCount = rowCount;
                metrics.checksum = checksum;
                return metrics;
            }

            @Override
            public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) {
                SliceSignal signal = new SliceSignal();
                signal.sliceKey = sliceKey;
                signal.sliceType = sliceColumn;
                signal.rowCount = rowCount;
                signal.checksum = checksum;
                List<SliceSignal> result = new ArrayList<>();
                result.add(signal);
                return result;
            }
        };
    }

    private SignalReader emptySignalReader() {
        return new SignalReader() {
            @Override
            public SummaryMetrics readSummary(ReadRequest request) {
                return new SummaryMetrics();
            }

            @Override
            public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) {
                return new ArrayList<>();
            }
        };
    }

    private RoutingSignalReader routingSignalReader(String sliceKey, long rowCount, String checksum) {
        return request -> {
            SliceSignal signal = new SliceSignal();
            signal.sliceKey = sliceKey;
            signal.sliceType = "routing";
            signal.rowCount = rowCount;
            signal.checksum = checksum;
            List<SliceSignal> result = new ArrayList<>();
            result.add(signal);
            return result;
        };
    }

    private RowStreamReader emptyRowReader() {
        return (request, visitor) -> {
        };
    }

    private RowStreamReader rowReader(Map<String, Object> row) {
        return (request, visitor) -> visitor.accept(row);
    }

    private Map<String, Object> row(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key1, value1);
        row.put(key2, value2);
        return row;
    }
}
