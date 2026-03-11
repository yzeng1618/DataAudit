package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.DataReader;
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

    public SummaryMetrics summarize(DataReader reader, TaskFileSpec spec, ReadRequest request) throws Exception {
        List<Map<String, Object>> rows = reader.readRows(request);
        SummaryMetrics metrics = new SummaryMetrics();
        metrics.rowCount = rows.size();
        long checksumSum = 0L;
        long checksumXor = 0L;
        Map<String, Set<String>> distinctValues = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = normalizationService.normalizeRow(spec, row);
            String canonical = normalizationService.canonicalRow(normalized);
            long currentHash = hashProvider.hash64(canonical);
            checksumSum += currentHash;
            checksumXor ^= currentHash;
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
        for (Map.Entry<String, Set<String>> entry : distinctValues.entrySet()) {
            metrics.distinctCount.put(entry.getKey(), (long) entry.getValue().size());
        }
        metrics.checksum = Long.toUnsignedString(checksumSum) + ":" + Long.toUnsignedString(checksumXor);
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
}

