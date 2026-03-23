package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public class SchemaEngine {
    public List<String> compare(TaskFileSpec spec, SchemaModel source, SchemaModel target) {
        Map<String, SchemaModel.Column> sourceColumns = logicalColumns(spec, source);
        Map<String, SchemaModel.Column> targetColumns = logicalColumns(spec, target);
        List<String> issues = new ArrayList<>();

        for (Map.Entry<String, SchemaModel.Column> entry : sourceColumns.entrySet()) {
            SchemaModel.Column targetColumn = targetColumns.get(entry.getKey());
            if (targetColumn == null) {
                issues.add("Missing target column: " + entry.getKey());
                continue;
            }
            if (!normalizeType(entry.getValue().type).equals(normalizeType(targetColumn.type))) {
                if (!isCompatible(spec, entry.getValue().type, targetColumn.type)) {
                    issues.add("Type mismatch for column " + entry.getKey() + ": " + entry.getValue().type + " vs " + targetColumn.type);
                }
            }
        }

        if ("strict".equalsIgnoreCase(spec.semantics.ddl.mode)) {
            for (String column : targetColumns.keySet()) {
                if (!sourceColumns.containsKey(column)) {
                    issues.add("Unexpected target column in strict mode: " + column);
                }
            }
        }
        return issues;
    }

    private Map<String, SchemaModel.Column> logicalColumns(TaskFileSpec spec, SchemaModel schema) {
        Map<String, SchemaModel.Column> result = new LinkedHashMap<>();
        Set<String> requestedColumns = requestedColumns(spec);
        for (SchemaModel.Column column : schema.columns) {
            String logicalName = spec.semantics.ddl.renameMapping.getOrDefault(column.name, column.name);
            if (!requestedColumns.isEmpty() && !matchesRequestedColumn(spec, requestedColumns, column.name, logicalName)) {
                continue;
            }
            column.logicalName = logicalName;
            result.put(logicalName, column);
        }
        return result;
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "unknown";
        }
        String normalized = type.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("decimal")) {
            return "decimal";
        }
        if (normalized.startsWith("varchar") || normalized.startsWith("char") || "text".equals(normalized) || "string".equals(normalized)) {
            return "string";
        }
        if ("integer".equals(normalized) || "int".equals(normalized)) {
            return "int";
        }
        if ("bigint".equals(normalized) || "long".equals(normalized)) {
            return "long";
        }
        if ("double precision".equals(normalized)) {
            return "double";
        }
        return normalized;
    }

    private boolean isCompatible(TaskFileSpec spec, String from, String to) {
        if ("logical_only".equalsIgnoreCase(spec.semantics.ddl.mode)) {
            return true;
        }
        String normalizedFrom = normalizeType(from);
        String normalizedTo = normalizeType(to);
        if (normalizedFrom.equals(normalizedTo)) {
            return true;
        }
        if (isCompatibleNumericPair(normalizedFrom, normalizedTo)) {
            return true;
        }
        for (TaskFileSpec.TypeRuleSpec rule : spec.semantics.ddl.typeRules) {
            if (rule.from != null && rule.to != null
                    && normalizeType(rule.from).equalsIgnoreCase(normalizedFrom)
                    && normalizeType(rule.to).equalsIgnoreCase(normalizedTo)
                    && ("allow".equalsIgnoreCase(rule.action) || "warn".equalsIgnoreCase(rule.action))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> requestedColumns(TaskFileSpec spec) {
        Set<String> requestedColumns = new LinkedHashSet<>();
        if (spec.object == null || spec.object.columns == null) {
            return requestedColumns;
        }
        requestedColumns.addAll(spec.object.columns);
        return requestedColumns;
    }

    private boolean matchesRequestedColumn(TaskFileSpec spec,
                                          Set<String> requestedColumns,
                                          String physicalName,
                                          String logicalName) {
        if (requestedColumns.contains(physicalName) || requestedColumns.contains(logicalName)) {
            return true;
        }
        for (String requestedColumn : requestedColumns) {
            String mappedColumn = spec.semantics.ddl.renameMapping.get(requestedColumn);
            if (physicalName.equals(mappedColumn) || logicalName.equals(mappedColumn)) {
                return true;
            }
            for (Map.Entry<String, String> entry : spec.semantics.ddl.renameMapping.entrySet()) {
                if (requestedColumn.equals(entry.getValue())
                        && (physicalName.equals(entry.getKey()) || logicalName.equals(entry.getKey()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isCompatibleNumericPair(String left, String right) {
        return ("int".equals(left) && "long".equals(right))
                || ("long".equals(left) && "int".equals(right));
    }
}
