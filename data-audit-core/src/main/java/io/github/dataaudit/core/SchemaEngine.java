package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        if ("strict".equalsIgnoreCase(spec.ddl.mode)) {
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
        for (SchemaModel.Column column : schema.columns) {
            String logicalName = spec.ddl.renameMapping.getOrDefault(column.name, column.name);
            if (!spec.object.columns.include.isEmpty() && !spec.object.columns.include.contains(column.name) && !spec.object.columns.include.contains(logicalName)) {
                continue;
            }
            column.logicalName = logicalName;
            result.put(logicalName, column);
        }
        return result;
    }

    private String normalizeType(String type) {
        return type == null ? "unknown" : type.toLowerCase(Locale.ROOT);
    }

    private boolean isCompatible(TaskFileSpec spec, String from, String to) {
        if ("logical_only".equalsIgnoreCase(spec.ddl.mode)) {
            return true;
        }
        for (TaskFileSpec.TypeRuleSpec rule : spec.ddl.typeRules) {
            if (rule.from != null && rule.to != null
                    && rule.from.equalsIgnoreCase(from)
                    && rule.to.equalsIgnoreCase(to)
                    && ("allow".equalsIgnoreCase(rule.action) || "warn".equalsIgnoreCase(rule.action))) {
                return true;
            }
        }
        return false;
    }
}

