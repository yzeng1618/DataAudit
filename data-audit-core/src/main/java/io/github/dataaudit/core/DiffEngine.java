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
    private final NormalizationService normalizationService;

    public DiffEngine(NormalizationService normalizationService) {
        this.normalizationService = normalizationService;
    }

    public DiffResult diff(RowStreamReader source, RowStreamReader target, TaskFileSpec spec, String sliceKey) throws Exception {
        List<Map<String, Object>> sourceRows = collectRows(source, requestFromSlice(spec, sliceKey));
        List<Map<String, Object>> targetRows = collectRows(target, requestFromSlice(spec, sliceKey));
        return diffRows(sourceRows, targetRows, spec, sliceKey);
    }

    public DiffResult diffRows(List<Map<String, Object>> sourceRows,
                               List<Map<String, Object>> targetRows,
                               TaskFileSpec spec,
                               String sliceKey) {
        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return keyedDiff(sourceRows, targetRows, spec, sliceKey);
        }
        return keylessDiff(sourceRows, targetRows, spec, sliceKey);
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
        int sampleLimit = 500;
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
        int sampleLimit = 500;
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
        List<Map<String, Object>> rows = new ArrayList<>();
        reader.scanRows(request, rows::add);
        return rows;
    }
}
