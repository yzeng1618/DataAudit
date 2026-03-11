package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;

public class SpecValidator {
    public List<String> validate(TaskFileSpec spec) {
        List<String> issues = new ArrayList<>();
        if (spec.task == null || isBlank(spec.task.name)) {
            issues.add("task.name is required");
        }
        if (spec.source == null || isBlank(spec.source.type)) {
            issues.add("source.type is required");
        }
        if (spec.target == null || isBlank(spec.target.type)) {
            issues.add("target.type is required");
        }
        if (spec.boundary == null || isBlank(spec.boundary.type)) {
            issues.add("boundary.type is required");
        }
        if (spec.source != null && !isSupportedEndpointType(spec.source.type)) {
            issues.add("source.type must be jdbc or iceberg");
        }
        if (spec.target != null && !isSupportedEndpointType(spec.target.type)) {
            issues.add("target.type must be jdbc or iceberg");
        }
        if (spec.source != null && "jdbc".equalsIgnoreCase(spec.source.type) && isBlank(spec.source.table) && isBlank(spec.source.query)) {
            issues.add("source.table or source.query is required for jdbc");
        }
        if (spec.target != null && "jdbc".equalsIgnoreCase(spec.target.type) && isBlank(spec.target.table) && isBlank(spec.target.query)) {
            issues.add("target.table or target.query is required for jdbc");
        }
        if (spec.source != null && "iceberg".equalsIgnoreCase(spec.source.type) && isBlank(spec.source.table) && isBlank(spec.source.location)) {
            issues.add("source.table or source.location is required for iceberg");
        }
        if (spec.target != null && "iceberg".equalsIgnoreCase(spec.target.type) && isBlank(spec.target.table) && isBlank(spec.target.location)) {
            issues.add("target.table or target.location is required for iceberg");
        }
        return issues;
    }

    private boolean isSupportedEndpointType(String type) {
        return "jdbc".equalsIgnoreCase(type) || "iceberg".equalsIgnoreCase(type);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
