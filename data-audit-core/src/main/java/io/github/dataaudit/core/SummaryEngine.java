package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class SummaryEngine {
    private final NormalizationService normalizationService;
    private final HashProvider hashProvider;

    public SummaryEngine(NormalizationService normalizationService, HashProvider hashProvider) {
        this.normalizationService = normalizationService;
        this.hashProvider = hashProvider;
    }

    public SummaryMetrics summarize(RowStreamReader reader, TaskFileSpec spec, ReadRequest request) throws Exception {
        SummaryMetrics metrics = new SummaryMetrics();
        long[] checksum = new long[2];
        Map<String, Set<String>> distinctValues = new LinkedHashMap<>();
        reader.scanRows(request, row -> accumulate(metrics, checksum, distinctValues, spec, row));
        finalizeDistinctCounts(metrics, distinctValues);
        metrics.checksum = Long.toUnsignedString(checksum[0]) + ":" + Long.toUnsignedString(checksum[1]);
        return metrics;
    }

    public SummaryMetrics summarizeRows(List<Map<String, Object>> rows, TaskFileSpec spec) {
        SummaryMetrics metrics = new SummaryMetrics();
        long[] checksum = new long[2];
        Map<String, Set<String>> distinctValues = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            accumulate(metrics, checksum, distinctValues, spec, row);
        }
        finalizeDistinctCounts(metrics, distinctValues);
        metrics.checksum = Long.toUnsignedString(checksum[0]) + ":" + Long.toUnsignedString(checksum[1]);
        return metrics;
    }

    public boolean equivalent(SummaryMetrics source, SummaryMetrics target) {
        return source.rowCount == target.rowCount
                && safeEquals(source.checksum, target.checksum)
                && source.nullCount.equals(target.nullCount)
                && source.minValues.equals(target.minValues)
                && source.maxValues.equals(target.maxValues)
                && source.distinctCount.equals(target.distinctCount);
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private void accumulate(SummaryMetrics metrics,
                            long[] checksum,
                            Map<String, Set<String>> distinctValues,
                            TaskFileSpec spec,
                            Map<String, Object> row) {
        metrics.rowCount++;
        Map<String, Object> normalized = normalizationService.normalizeRow(spec, row);
        String canonical = normalizationService.canonicalRow(normalized);
        long currentHash = hashProvider.hash64(canonical);
        checksum[0] += currentHash;
        checksum[1] ^= currentHash;
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                metrics.nullCount.merge(key, 1L, Long::sum);
                continue;
            }
            String text = String.valueOf(value);
            metrics.minValues.merge(key, text, (left, right) -> left.compareTo(right) <= 0 ? left : right);
            metrics.maxValues.merge(key, text, (left, right) -> left.compareTo(right) >= 0 ? left : right);
            distinctValues.computeIfAbsent(key, ignored -> new TreeSet<>()).add(text);
        }
    }

    private void finalizeDistinctCounts(SummaryMetrics metrics, Map<String, Set<String>> distinctValues) {
        for (Map.Entry<String, Set<String>> entry : distinctValues.entrySet()) {
            metrics.distinctCount.put(entry.getKey(), (long) entry.getValue().size());
        }
    }
}
