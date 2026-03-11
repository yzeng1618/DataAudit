package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.TaskFileSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NormalizationService {
    public Map<String, Object> normalizeRow(TaskFileSpec spec, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<String> columns = projectedColumns(spec, row);
        for (String column : columns) {
            String logicalName = spec.ddl.renameMapping.getOrDefault(column, column);
            Object value = row.get(column);
            normalized.put(logicalName, normalizeValue(spec, logicalName, value));
        }
        return normalized;
    }

    public String canonicalRow(Map<String, Object> normalizedRow) {
        List<String> keys = new ArrayList<>(normalizedRow.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            builder.append(key).append('=').append(stringify(normalizedRow.get(key))).append('|');
        }
        return builder.toString();
    }

    private List<String> projectedColumns(TaskFileSpec spec, Map<String, Object> row) {
        if (spec.object != null && spec.object.columns != null && spec.object.columns.include != null && !spec.object.columns.include.isEmpty()) {
            return spec.object.columns.include;
        }
        return new ArrayList<>(row.keySet());
    }

    private Object normalizeValue(TaskFileSpec spec, String column, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String text = (String) value;
            if (Boolean.TRUE.equals(spec.normalization.trimString)) {
                text = text.trim();
            }
            if (Boolean.TRUE.equals(spec.normalization.emptyAsNull) && text.isEmpty()) {
                return null;
            }
            if (spec.normalization.caseInsensitiveColumns.contains(column)) {
                text = text.toLowerCase(Locale.ROOT);
            }
            return text;
        }
        if (value instanceof BigDecimal) {
            Integer scale = spec.normalization.decimalScale.get(column);
            if (scale != null) {
                return ((BigDecimal) value).setScale(scale, RoundingMode.HALF_UP);
            }
            return value;
        }
        if (value instanceof Timestamp) {
            ZoneId zoneId = ZoneId.of(spec.normalization.timezone == null ? "UTC" : spec.normalization.timezone);
            Instant instant = ((Timestamp) value).toInstant();
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zoneId));
        }
        return value;
    }

    private String stringify(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }
}

