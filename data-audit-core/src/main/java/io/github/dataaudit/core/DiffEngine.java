// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class DiffEngine {
    private static final String LIMIT_MAX_IN_MEMORY_ROWS = "max_in_memory_rows";
    private static final int DEFAULT_BOUNDED_BUCKETS = 16;

    private final NormalizationService normalizationService;

    public DiffEngine(NormalizationService normalizationService) {
        this.normalizationService = normalizationService;
    }

    public DiffResult diff(RowStreamReader source, RowStreamReader target, TaskFileSpec spec, String sliceKey) throws Exception {
        if (hasKey(spec)) {
            return boundedKeyedDiff(source, target, spec, sliceKey);
        }
        List<Map<String, Object>> sourceRows;
        List<Map<String, Object>> targetRows;
        try {
            sourceRows = collectRows(source, requestFromSlice(spec, sliceKey), maxInMemoryRows(spec));
            targetRows = collectRows(target, requestFromSlice(spec, sliceKey), maxInMemoryRows(spec));
        } catch (ResourceLimitExceededException e) {
            return limitExceeded("keyless_diff_resource_limit", e.limitType);
        }
        return diffRows(sourceRows, targetRows, spec, sliceKey);
    }

    public DiffResult diffRows(List<Map<String, Object>> sourceRows,
                               List<Map<String, Object>> targetRows,
                               TaskFileSpec spec,
                               String sliceKey) {
        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return keyedDiff(sourceRows, targetRows, spec, sliceKey);
        }
        long maxRows = maxInMemoryRows(spec);
        if (sourceRows.size() > maxRows || targetRows.size() > maxRows) {
            return limitExceeded("keyless_diff_resource_limit", LIMIT_MAX_IN_MEMORY_ROWS);
        }
        return keylessDiff(sourceRows, targetRows, spec, sliceKey);
    }

    private DiffResult boundedKeyedDiff(RowStreamReader source,
                                        RowStreamReader target,
                                        TaskFileSpec spec,
                                        String sliceKey) throws Exception {
        long maxRows = maxInMemoryRows(spec);
        try {
            DiffResult result = keyedDiff(
                    collectRows(source, requestFromSlice(spec, sliceKey), maxRows),
                    collectRows(target, requestFromSlice(spec, sliceKey), maxRows),
                    spec,
                    sliceKey
            );
            result.resourceBounded = true;
            return result;
        } catch (ResourceLimitExceededException ignored) {
            return bucketedKeyedDiff(source, target, spec, sliceKey, maxRows);
        }
    }

    private DiffResult bucketedKeyedDiff(RowStreamReader source,
                                         RowStreamReader target,
                                         TaskFileSpec spec,
                                         String sliceKey,
                                         long maxRows) throws Exception {
        DiffResult merged = new DiffResult();
        merged.resourceBounded = true;
        int bucketCount = boundedBucketCount(spec, maxRows);
        String bucketColumn = spec.object.key.get(0);
        for (int bucketId = 0; bucketId < bucketCount; bucketId++) {
            ReadRequest request = requestFromSliceAndBucket(spec, sliceKey, bucketColumn, bucketCount, bucketId);
            DiffResult partial;
            try {
                partial = keyedDiff(
                        collectRows(source, request, maxRows),
                        collectRows(target, request, maxRows),
                        spec,
                        sliceKey
                );
            } catch (ResourceLimitExceededException e) {
                return limitExceeded("keyed_diff_resource_limit", e.limitType);
            }
            partial.resourceBounded = true;
            mergeDiff(merged, partial, maxDiffSamples(spec));
        }
        return merged;
    }

    private DiffResult keyedDiff(List<Map<String, Object>> sourceRows,
                                 List<Map<String, Object>> targetRows,
                                 TaskFileSpec spec,
                                 String sliceKey) {
        DiffResult result = new DiffResult();
        Map<String, String> left = new TreeMap<>();
        Map<String, String> right = new TreeMap<>();
        for (Map<String, Object> row : sourceRows) {
            Map<String, Object> normalized = normalizationService.normalizeRow(spec, row);
            left.put(buildKey(spec, normalized), normalizationService.canonicalRow(normalized));
        }
        for (Map<String, Object> row : targetRows) {
            Map<String, Object> normalized = normalizationService.normalizeRow(spec, row);
            right.put(buildKey(spec, normalized), normalizationService.canonicalRow(normalized));
        }
        int sampleLimit = maxDiffSamples(spec);
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String rightValue = right.remove(entry.getKey());
            if (rightValue == null) {
                addSample(result, "missing_in_target", entry.getKey(), entry.getValue(), null, sliceKey, sampleLimit);
            } else if (!entry.getValue().equals(rightValue)) {
                addSample(result, "row_mismatch", entry.getKey(), entry.getValue(), rightValue, sliceKey, sampleLimit);
            }
        }
        for (Map.Entry<String, String> entry : right.entrySet()) {
            addSample(result, "extra_in_target", entry.getKey(), null, entry.getValue(), sliceKey, sampleLimit);
        }
        if (!result.samples.isEmpty()) {
            result.consistent = false;
            result.rootCause = "keyed_diff_detected";
        }
        return result;
    }

    private DiffResult keylessDiff(List<Map<String, Object>> sourceRows,
                                   List<Map<String, Object>> targetRows,
                                   TaskFileSpec spec,
                                   String sliceKey) {
        DiffResult result = new DiffResult();
        Map<String, Integer> left = asMultiset(sourceRows, spec);
        Map<String, Integer> right = asMultiset(targetRows, spec);
        int sampleLimit = maxDiffSamples(spec);
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            int rightCount = right.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() != rightCount) {
                addSample(result, "multiset_mismatch", entry.getKey(), String.valueOf(entry.getValue()), String.valueOf(rightCount), sliceKey, sampleLimit);
            }
            right.remove(entry.getKey());
        }
        for (Map.Entry<String, Integer> entry : right.entrySet()) {
            addSample(result, "multiset_extra_target", entry.getKey(), "0", String.valueOf(entry.getValue()), sliceKey, sampleLimit);
        }
        if (!result.samples.isEmpty()) {
            result.consistent = false;
            result.rootCause = "keyless_diff_detected";
        }
        return result;
    }

    private Map<String, Integer> asMultiset(List<Map<String, Object>> rows, TaskFileSpec spec) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String canonical = normalizationService.canonicalRow(normalizationService.normalizeRow(spec, row));
            result.merge(canonical, 1, Integer::sum);
        }
        return result;
    }

    private String buildKey(TaskFileSpec spec, Map<String, Object> normalized) {
        List<String> parts = new ArrayList<>();
        for (String key : spec.object.key) {
            String logical = spec.semantics.ddl.renameMapping.getOrDefault(key, key);
            parts.add(logical + "=" + String.valueOf(normalized.get(logical)));
        }
        return String.join("|", parts);
    }

    private ReadRequest requestFromSlice(TaskFileSpec spec, String sliceKey) {
        int[] bucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        if (bucket != null && spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return ReadRequestFactory.bucketRequest(spec, spec.object.key.get(0), bucket[1], bucket[0]);
        }
        if (sliceKey != null && sliceKey.contains("=")) {
            String[] parts = sliceKey.split("=", 2);
            return ReadRequestFactory.sliceRequest(spec, parts[0], parts[1]);
        }
        return ReadRequestFactory.baseRequest(spec);
    }

    private ReadRequest requestFromSliceAndBucket(TaskFileSpec spec,
                                                 String sliceKey,
                                                 String bucketColumn,
                                                 int bucketCount,
                                                 int bucketId) {
        int[] virtualBucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        if (virtualBucket != null) {
            return ReadRequestFactory.bucketRequest(spec, bucketColumn, virtualBucket[1], virtualBucket[0]);
        }
        String sliceColumn = null;
        String sliceValue = null;
        if (sliceKey != null && sliceKey.contains("=")) {
            String[] parts = sliceKey.split("=", 2);
            sliceColumn = parts[0];
            sliceValue = parts[1];
        }
        return ReadRequestFactory.sampleRequest(spec, bucketColumn, bucketCount, bucketId, sliceColumn, sliceValue);
    }

    private void addSample(DiffResult result,
                           String type,
                           String key,
                           String sourceValue,
                           String targetValue,
                           String sliceKey,
                           int sampleLimit) {
        result.consistent = false;
        if (result.samples.size() >= sampleLimit) {
            return;
        }
        DiffResult.DiffSample sample = new DiffResult.DiffSample();
        sample.type = type;
        sample.key = key;
        sample.sourceValue = sourceValue;
        sample.targetValue = targetValue;
        sample.sliceKey = sliceKey;
        result.samples.add(sample);
    }

    private List<Map<String, Object>> collectRows(RowStreamReader reader, ReadRequest request) throws Exception {
        return collectRows(reader, request, Long.MAX_VALUE);
    }

    private List<Map<String, Object>> collectRows(RowStreamReader reader, ReadRequest request, long maxRows) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        reader.scanRows(request, row -> {
            if (rows.size() >= maxRows) {
                throw new ResourceLimitExceededException(LIMIT_MAX_IN_MEMORY_ROWS);
            }
            rows.add(row);
        });
        return rows;
    }

    private void mergeDiff(DiffResult merged, DiffResult partial, int sampleLimit) {
        merged.consistent = merged.consistent && partial.consistent;
        merged.sampled = merged.sampled || partial.sampled;
        merged.resourceBounded = merged.resourceBounded || partial.resourceBounded;
        merged.limitExceeded = merged.limitExceeded || partial.limitExceeded;
        merged.limitType = merged.limitType == null ? partial.limitType : merged.limitType;
        merged.fallbackReason = merged.fallbackReason == null ? partial.fallbackReason : merged.fallbackReason;
        for (DiffResult.DiffSample sample : partial.samples) {
            if (merged.samples.size() >= sampleLimit) {
                break;
            }
            merged.samples.add(sample);
        }
        if (!partial.consistent) {
            merged.rootCause = partial.rootCause;
        }
    }

    private DiffResult limitExceeded(String rootCause, String limitType) {
        DiffResult result = new DiffResult();
        result.consistent = false;
        result.sampled = true;
        result.resourceBounded = true;
        result.limitExceeded = true;
        result.limitType = limitType;
        result.fallbackReason = limitType + "_exceeded";
        result.rootCause = rootCause;
        return result;
    }

    private boolean hasKey(TaskFileSpec spec) {
        return spec.object != null && spec.object.key != null && !spec.object.key.isEmpty();
    }

    private long maxInMemoryRows(TaskFileSpec spec) {
        if (spec == null || spec.resources == null || spec.resources.maxInMemoryRows == null) {
            return 100_000L;
        }
        return spec.resources.maxInMemoryRows;
    }

    private int maxDiffSamples(TaskFileSpec spec) {
        if (spec == null || spec.resources == null || spec.resources.maxDiffSamples == null) {
            return 500;
        }
        return spec.resources.maxDiffSamples;
    }

    private int boundedBucketCount(TaskFileSpec spec, long maxRows) {
        long estimatedRows = spec.object == null || spec.object.estimatedRows == null
                ? -1L
                : spec.object.estimatedRows;
        if (estimatedRows <= 0L || maxRows <= 0L) {
            return DEFAULT_BOUNDED_BUCKETS;
        }
        long buckets = (estimatedRows + maxRows - 1L) / maxRows;
        buckets = Math.max(DEFAULT_BOUNDED_BUCKETS, buckets);
        buckets = Math.min(256L, buckets);
        return (int) buckets;
    }

    private static final class ResourceLimitExceededException extends RuntimeException {
        private final String limitType;

        private ResourceLimitExceededException(String limitType) {
            this.limitType = limitType;
        }
    }
}
