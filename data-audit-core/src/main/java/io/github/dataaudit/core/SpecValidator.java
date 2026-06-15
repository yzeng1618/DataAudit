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
        validateResources(spec, issues);
        return issues;
    }

    private void validateResources(TaskFileSpec spec, List<String> issues) {
        if (spec.resources == null) {
            return;
        }
        if (spec.resources.maxInMemoryRows == null || spec.resources.maxInMemoryRows <= 0L) {
            issues.add("resources.max_in_memory_rows must be positive");
        }
        if (spec.resources.maxDiffSamples == null || spec.resources.maxDiffSamples <= 0) {
            issues.add("resources.max_diff_samples must be positive");
        }
        if (spec.resources.globalTimeoutMillis == null || spec.resources.globalTimeoutMillis < 0L) {
            issues.add("resources.global_timeout_millis must be non-negative");
        }
        if (spec.resources.queryTimeoutMillis == null || spec.resources.queryTimeoutMillis < 0L) {
            issues.add("resources.query_timeout_millis must be non-negative");
        }
        if (spec.resources.segmentParallelism == null || spec.resources.segmentParallelism < 1) {
            issues.add("resources.segment_parallelism must be at least 1");
        }
    }

    private void validateEndpoint(String label,
                                  TaskFileSpec.EndpointSpec endpoint,
                                  TaskFileSpec spec,
                                  List<String> issues) {
        if (endpoint == null || isBlank(endpoint.type)) {
            issues.add(label + ".type is required");
            return;
        }
        if (isDesignReservedEndpointType(endpoint.type)) {
            issues.add(label + ".type=" + endpoint.type.toLowerCase(Locale.ROOT) + " "
                    + displayName(endpoint.type)
                    + " native support is design-reserved; use supported JDBC, Trino, or Iceberg paths");
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

    private boolean isDesignReservedEndpointType(String type) {
        return "hudi".equalsIgnoreCase(type)
                || "delta".equalsIgnoreCase(type)
                || "paimon".equalsIgnoreCase(type);
    }

    private String displayName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        return type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1).toLowerCase(Locale.ROOT);
    }

    private boolean usesTrinoQueryPlane(TaskFileSpec.EndpointSpec endpoint) {
        return endpoint != null
                && ("sql".equalsIgnoreCase(endpoint.type) || "trino".equalsIgnoreCase(endpoint.type));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
