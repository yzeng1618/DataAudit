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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NormalizationService {
    public Map<String, Object> normalizeRow(TaskFileSpec spec, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<String> columns = projectedColumns(spec, row);
        for (String column : columns) {
            String logicalName = spec.semantics.ddl.renameMapping.getOrDefault(column, column);
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
        if (spec.object != null && spec.object.columns != null && !spec.object.columns.isEmpty()) {
            List<String> resolved = resolveColumns(spec, row.keySet(), spec.object.columns);
            if (!resolved.isEmpty()) {
                return resolved;
            }
        }
        return new ArrayList<>(row.keySet());
    }

    private List<String> resolveColumns(TaskFileSpec spec, Set<String> availableColumns, List<String> requestedColumns) {
        List<String> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String requested : requestedColumns) {
            String column = resolveColumn(spec, availableColumns, requested);
            if (column != null && seen.add(column)) {
                resolved.add(column);
            }
        }
        return resolved;
    }

    private String resolveColumn(TaskFileSpec spec, Set<String> availableColumns, String requestedColumn) {
        if (availableColumns.contains(requestedColumn)) {
            return requestedColumn;
        }
        String mappedColumn = spec.semantics.ddl.renameMapping.get(requestedColumn);
        if (mappedColumn != null && availableColumns.contains(mappedColumn)) {
            return mappedColumn;
        }
        for (Map.Entry<String, String> entry : spec.semantics.ddl.renameMapping.entrySet()) {
            if (requestedColumn.equals(entry.getValue()) && availableColumns.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Object normalizeValue(TaskFileSpec spec, String column, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String text = (String) value;
            if (Boolean.TRUE.equals(spec.normalize.trimString)) {
                text = text.trim();
            }
            if (Boolean.TRUE.equals(spec.normalize.emptyAsNull) && text.isEmpty()) {
                return null;
            }
            if (spec.normalize.caseInsensitiveColumns.contains(column)) {
                text = text.toLowerCase(Locale.ROOT);
            }
            return text;
        }
        if (value instanceof BigDecimal) {
            Integer scale = spec.normalize.decimalScale.get(column);
            if (scale != null) {
                return ((BigDecimal) value).setScale(scale, RoundingMode.HALF_UP);
            }
            return value;
        }
        if (value instanceof Number && spec.normalize.decimalScale.containsKey(column)) {
            Integer scale = spec.normalize.decimalScale.get(column);
            return new BigDecimal(String.valueOf(value)).setScale(scale, RoundingMode.HALF_UP);
        }
        if (value instanceof Timestamp) {
            ZoneId zoneId = ZoneId.of(spec.normalize.timezone == null ? "UTC" : spec.normalize.timezone);
            Instant instant = ((Timestamp) value).toInstant();
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zoneId));
        }
        return value;
    }

    private String stringify(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }
}
