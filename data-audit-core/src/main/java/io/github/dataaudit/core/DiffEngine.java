package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.DataReader;
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

    public DiffResult diff(DataReader source, DataReader target, TaskFileSpec spec, String segmentKey) throws Exception {
        ReadRequest sourceRequest = requestFromSegment(segmentKey);
        ReadRequest targetRequest = requestFromSegment(segmentKey);
        List<Map<String, Object>> sourceRows = source.readRows(sourceRequest);
        List<Map<String, Object>> targetRows = target.readRows(targetRequest);
        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return keyedDiff(sourceRows, targetRows, spec, segmentKey);
        }
        return keylessDiff(sourceRows, targetRows, spec, segmentKey);
    }

    private DiffResult keyedDiff(List<Map<String, Object>> sourceRows,
                                 List<Map<String, Object>> targetRows,
                                 TaskFileSpec spec,
                                 String segmentKey) {
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
        int sampleLimit = spec.compare.diff.maxSamples == null ? 500 : spec.compare.diff.maxSamples;
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String rightValue = right.remove(entry.getKey());
            if (rightValue == null) {
                addSample(result, "missing_in_target", entry.getKey(), entry.getValue(), null, segmentKey, sampleLimit);
            } else if (!entry.getValue().equals(rightValue)) {
                addSample(result, "row_mismatch", entry.getKey(), entry.getValue(), rightValue, segmentKey, sampleLimit);
            }
        }
        for (Map.Entry<String, String> entry : right.entrySet()) {
            addSample(result, "extra_in_target", entry.getKey(), null, entry.getValue(), segmentKey, sampleLimit);
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
                                   String segmentKey) {
        DiffResult result = new DiffResult();
        Map<String, Integer> left = asMultiset(sourceRows, spec);
        Map<String, Integer> right = asMultiset(targetRows, spec);
        int sampleLimit = spec.compare.diff.maxSamples == null ? 500 : spec.compare.diff.maxSamples;
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            int rightCount = right.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() != rightCount) {
                addSample(result, "multiset_mismatch", entry.getKey(), String.valueOf(entry.getValue()), String.valueOf(rightCount), segmentKey, sampleLimit);
            }
            right.remove(entry.getKey());
        }
        for (Map.Entry<String, Integer> entry : right.entrySet()) {
            addSample(result, "multiset_extra_target", entry.getKey(), "0", String.valueOf(entry.getValue()), segmentKey, sampleLimit);
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
            String logical = spec.ddl.renameMapping.getOrDefault(key, key);
            parts.add(logical + "=" + String.valueOf(normalized.get(logical)));
        }
        return String.join("|", parts);
    }

    private ReadRequest requestFromSegment(String segmentKey) {
        ReadRequest request = new ReadRequest();
        if (segmentKey != null && segmentKey.contains("=")) {
            String[] parts = segmentKey.split("=", 2);
            request.segmentColumn = parts[0];
            request.segmentValue = parts[1];
        }
        return request;
    }

    private void addSample(DiffResult result,
                           String type,
                           String key,
                           String sourceValue,
                           String targetValue,
                           String segmentKey,
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
        sample.segmentKey = segmentKey;
        result.samples.add(sample);
    }
}

