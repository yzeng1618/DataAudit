// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.analysis;

import io.github.dataaudit.ai.model.AuditPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AnomalyExtractor {
    public Map<String, Object> extract(AuditPlan plan, Map<String, Object> auditResult) {
        Map<String, Object> features = new LinkedHashMap<>();
        Map<String, Object> resultSection = mapValue(auditResult, "result");
        Map<String, Object> evidenceSection = mapValue(auditResult, "evidence");
        features.put("status", firstValue(auditResult, resultSection, "status"));
        features.put("write_mode", plan.syncContext.writeMode);
        features.put("sync_mode", plan.syncContext.syncMode);
        features.put("risk_types", plan.riskAnalysis.stream().map(risk -> risk.riskType).toList());
        features.put("retrieved_cases", plan.retrievedCases);
        features.put("logs", firstNonNull(value(auditResult, "logs"), value(evidenceSection, "notes")));
        features.put("metrics", firstValue(auditResult, evidenceSection, "metrics"));
        features.put("diff_partition", firstNonNull(value(auditResult, "diff_partition"), firstSuspectSlice(resultSection)));
        features.put("checksum_equal", firstValue(auditResult, resultSection, "checksum_equal"));
        features.put("amount_sum_equal", firstValue(auditResult, resultSection, "amount_sum_equal"));
        features.put("status_distribution_equal", firstValue(auditResult, resultSection, "status_distribution_equal"));
        Long sourceCount = firstLong(
                value(auditResult, "source_count"),
                nestedValue(resultSection, "source_summary", "row_count"),
                nestedValue(evidenceSection, "global_signal", "source_summary", "row_count"));
        Long targetCount = firstLong(
                value(auditResult, "target_count"),
                nestedValue(resultSection, "target_summary", "row_count"),
                nestedValue(evidenceSection, "global_signal", "target_summary", "row_count"));
        if (sourceCount != null && targetCount != null && !sourceCount.equals(targetCount)) {
            features.put("row_count_mismatch", "source_count=" + sourceCount + ",target_count=" + targetCount);
            features.put("target_lower", targetCount < sourceCount);
        }
        String text = features.toString().toLowerCase(Locale.ROOT);
        if (text.contains("embedding_dim")) {
            features.put("embedding_dim_mismatch", true);
        }
        if (text.contains("partition") || features.get("diff_partition") != null) {
            features.put("partition_diff", true);
        }
        if (Boolean.FALSE.equals(features.get("checksum_equal")) || text.contains("checksum")) {
            features.put("checksum_mismatch", true);
        }
        if (text.contains("decimal") || text.contains("precision") || text.contains("scale")) {
            features.put("decimal_precision_signal", true);
        }
        if (text.contains("307") || text.contains("redirect") || text.contains("retry")
                || text.contains("stream load") || text.contains("rejected")) {
            features.put("doris_stream_load_retry", true);
        }
        if (text.contains("cdc") || text.contains("checkpoint") || text.contains("boundary")) {
            features.put("cdc_boundary_signal", true);
        }
        return features;
    }

    private Object value(Map<String, Object> values, String key) {
        return values == null ? null : values.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> values, String key) {
        Object value = value(values, key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object firstValue(Map<String, Object> first, Map<String, Object> second, String key) {
        return firstNonNull(value(first, key), value(second, key));
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Long firstLong(Object... values) {
        for (Object value : values) {
            Long longValue = longValue(value);
            if (longValue != null) {
                return longValue;
            }
        }
        return null;
    }

    private Object nestedValue(Map<String, Object> values, String first, String second) {
        return value(mapValue(values, first), second);
    }

    private Object nestedValue(Map<String, Object> values, String first, String second, String third) {
        return nestedValue(mapValue(values, first), second, third);
    }

    @SuppressWarnings("unchecked")
    private String firstSuspectSlice(Map<String, Object> resultSection) {
        Object slices = value(resultSection, "suspect_slices");
        if (slices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object key = ((Map<String, Object>) map).get("key");
                if (key == null) {
                    key = ((Map<String, Object>) map).get("slice_key");
                }
                return key == null ? null : String.valueOf(key);
            }
            return String.valueOf(first);
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
