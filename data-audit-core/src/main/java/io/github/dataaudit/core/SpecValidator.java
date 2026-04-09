package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpecValidator {
    public List<String> validate(TaskFileSpec spec) {
        List<String> issues = new ArrayList<>();
        if (spec.task == null || isBlank(spec.task.name)) {
            issues.add("task.name is required");
        }
        if (spec.boundary == null || isBlank(spec.boundary.type)) {
            issues.add("boundary.type is required");
        }
        validateEndpoint("source", spec.source, spec, issues);
        validateEndpoint("target", spec.target, spec, issues);
        if (spec.queryConnector != null && !isBlank(spec.queryConnector.type) && !"trino".equalsIgnoreCase(spec.queryConnector.type)) {
            issues.add("query_connector.type must be trino");
        }
        if (spec.object != null && spec.object.estimatedBytes != null && spec.object.estimatedBytes < 0L) {
            issues.add("object.estimated_bytes must be non-negative");
        }
        if (spec.planner != null && !isBlank(spec.planner.scaleOverride)) {
            String scale = spec.planner.scaleOverride.toLowerCase(Locale.ROOT);
            if (!List.of("small", "large", "xlarge").contains(scale)) {
                issues.add("planner.scale_override must be small, large or xlarge");
            }
        }
        if (spec.output == null || isBlank(spec.output.dir)) {
            issues.add("output.dir is required");
        }
        return issues;
    }

    private void validateEndpoint(String label,
                                  TaskFileSpec.EndpointSpec endpoint,
                                  TaskFileSpec spec,
                                  List<String> issues) {
        if (endpoint == null || isBlank(endpoint.type)) {
            issues.add(label + ".type is required");
            return;
        }
        if (!isSupportedEndpointType(endpoint.type)) {
            issues.add(label + ".type must be sql, trino, jdbc or iceberg");
            return;
        }
        if (usesTrinoQueryPlane(endpoint)) {
            if (spec.queryConnector == null) {
                issues.add("query_connector is required when " + label + ".type uses trino query plane");
            } else {
                if (isBlank(spec.queryConnector.uri)) {
                    issues.add("query_connector.uri is required when " + label + ".type uses trino query plane");
                }
                if (isBlank(spec.queryConnector.user)) {
                    issues.add("query_connector.user is required when " + label + ".type uses trino query plane");
                }
            }
            if (isBlank(endpoint.table) && isBlank(endpoint.query)) {
                issues.add(label + ".table or " + label + ".query is required for trino/sql endpoint");
            }
        }
        if ("jdbc".equalsIgnoreCase(endpoint.type)) {
            if (isBlank(endpoint.url)) {
                issues.add(label + ".url is required for jdbc");
            }
            if (isBlank(endpoint.table) && isBlank(endpoint.query)) {
                issues.add(label + ".table or " + label + ".query is required for jdbc");
            }
        }
        if ("iceberg".equalsIgnoreCase(endpoint.type)) {
            if (isBlank(endpoint.table) && isBlank(endpoint.location)) {
                issues.add(label + ".table or " + label + ".location is required for iceberg");
            }
        }
    }

    private boolean isSupportedEndpointType(String type) {
        return "sql".equalsIgnoreCase(type)
                || "trino".equalsIgnoreCase(type)
                || "jdbc".equalsIgnoreCase(type)
                || "iceberg".equalsIgnoreCase(type);
    }

    private boolean usesTrinoQueryPlane(TaskFileSpec.EndpointSpec endpoint) {
        return endpoint != null
                && ("sql".equalsIgnoreCase(endpoint.type) || "trino".equalsIgnoreCase(endpoint.type));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
