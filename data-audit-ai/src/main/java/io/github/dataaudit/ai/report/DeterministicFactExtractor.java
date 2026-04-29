package io.github.dataaudit.ai.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterministicFactExtractor {
    private DeterministicFactExtractor() {
    }

    public static Object value(Map<String, Object> result, String key, Object fallback) {
        Object direct = value(result, key);
        if (direct != null) {
            return direct;
        }
        Map<String, Object> resultSection = mapValue(result, "result");
        Object nestedResult = value(resultSection, key);
        if (nestedResult != null) {
            return nestedResult;
        }
        if ("diff_partition".equals(key)) {
            String firstSlice = firstSuspectSlice(resultSection);
            return firstSlice == null ? fallback : firstSlice;
        }
        if ("logs".equals(key)) {
            Map<String, Object> evidence = mapValue(result, "evidence");
            Object notes = value(evidence, "notes");
            return notes == null ? fallback : notes;
        }
        if ("metrics".equals(key)) {
            Map<String, Object> evidence = mapValue(result, "evidence");
            Object globalSignal = value(evidence, "global_signal");
            return globalSignal == null ? fallback : globalSignal;
        }
        return fallback;
    }

    public static Map<String, String> lockedFacts(Map<String, Object> result) {
        Map<String, String> facts = new LinkedHashMap<>();
        addFact(facts, "status", value(result, "status", null));
        addFact(facts, "proof_mode", value(result, "proof_mode", null));
        addFact(facts, "confidence", value(result, "confidence", null));
        addFact(facts, "diff_partition", value(result, "diff_partition", null));
        return facts;
    }

    private static void addFact(Map<String, String> facts, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            facts.put(key, String.valueOf(value));
        }
    }

    private static Object value(Map<String, Object> values, String key) {
        return values == null ? null : values.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> values, String key) {
        Object value = value(values, key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static String firstSuspectSlice(Map<String, Object> resultSection) {
        Object slices = value(resultSection, "suspect_slices");
        if (slices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object key = ((Map<String, Object>) map).get("slice_key");
                if (key == null) {
                    key = ((Map<String, Object>) map).get("key");
                }
                return key == null ? null : String.valueOf(key);
            }
            return String.valueOf(first);
        }
        return null;
    }
}
