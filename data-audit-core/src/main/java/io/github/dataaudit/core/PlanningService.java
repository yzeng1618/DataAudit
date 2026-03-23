package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;

public class PlanningService {
    static final long MAX_EXACT_ROWS = 100_000L;

    public ExecutionPlan plan(TaskFileSpec spec,
                              CapabilityDescriptor sourceCapability,
                              CapabilityDescriptor targetCapability,
                              BoundaryRef boundaryRef) {
        ExecutionPlan plan = new ExecutionPlan();
        plan.boundary = boundaryRef;
        plan.decisionTrace.add("boundary type: " + boundaryRef.type);
        plan.decisionTrace.add("boundary stable: " + boundaryRef.stable);

        if (!boundaryRef.stable) {
            plan.refuseReason = "unstable_boundary";
            plan.reason = boundaryRef.detail;
            plan.decisionTrace.add("refused because boundary is not stable: " + boundaryRef.detail);
            return plan;
        }

        long estimatedRows = spec.object == null || spec.object.estimatedRows == null ? -1L : spec.object.estimatedRows;
        boolean snapshotAware = "snapshot".equalsIgnoreCase(spec.boundary.type)
                && ("iceberg".equalsIgnoreCase(spec.source.type) || "iceberg".equalsIgnoreCase(spec.target.type));
        boolean trinoPreferred = hasTrinoEndpoint(spec)
                && spec.queryConnector != null
                && "trino".equalsIgnoreCase(spec.queryConnector.type);
        String sliceColumn = firstSliceColumn(spec);

        plan.decisionTrace.add("estimated rows: " + (estimatedRows < 0 ? "unknown" : estimatedRows));
        plan.decisionTrace.add("trino preferred: " + trinoPreferred);
        plan.decisionTrace.add("snapshot aware: " + snapshotAware);
        plan.decisionTrace.add("natural slice column: " + (sliceColumn == null ? "none" : sliceColumn));

        if (snapshotAware) {
            plan.objectClass = "lakehouse_object";
            plan.selectedPath = "boundary metadata -> schema -> signal -> localization -> drilldown";
            plan.executedLevels = levels("metadata", "schema", "signal", "localization", "drilldown");
            plan.signalBackend = "iceberg_native_metadata";
            plan.signalStrategy = "metadata_first";
            plan.localizationStrategy = sliceColumn == null ? "metadata_hint" : "natural_slice";
            plan.resumeStrategy = "suspect_slice";
            plan.reason = "snapshot boundary requires native iceberg metadata";
            return plan;
        }

        if (estimatedRows >= 0 && estimatedRows <= MAX_EXACT_ROWS) {
            plan.objectClass = "small_table_once";
            plan.selectedPath = "schema -> exact diff";
            plan.executedLevels = levels("schema", "exact_diff");
            plan.signalBackend = trinoPreferred ? "trino_exact_read" : connectorName(sourceCapability, targetCapability, "direct_exact_read");
            plan.signalStrategy = "exact_first";
            plan.localizationStrategy = "none";
            plan.resumeStrategy = "rerun";
            plan.shortCircuitReason = "estimated_rows_within_threshold";
            plan.reason = plan.shortCircuitReason;
            plan.decisionTrace.add("selected exact diff because estimated rows are within threshold");
            return plan;
        }

        plan.objectClass = snapshotAware ? "lakehouse_object" : "partitioned_big_table";
        plan.selectedPath = "gate -> signal -> localization -> drilldown";
        plan.executedLevels = levels("schema", "signal", "localization", "drilldown");
        plan.signalBackend = trinoPreferred ? "trino_grouped_signal" : connectorName(sourceCapability, targetCapability, "fallback_signal");
        plan.signalStrategy = trinoPreferred ? "grouped_pushdown" : "streaming_signal";
        plan.localizationStrategy = sliceColumn != null ? "natural_slice" : (hasKey(spec) ? "virtual_bucket" : "sample_first");
        plan.resumeStrategy = "suspect_slice";
        plan.reason = sliceColumn != null ? "large object uses natural slice localization" : "large object requires bucket or deterministic sampling";
        return plan;
    }

    private boolean hasTrinoEndpoint(TaskFileSpec spec) {
        return isTrinoEndpoint(spec.source) || isTrinoEndpoint(spec.target);
    }

    private boolean isTrinoEndpoint(TaskFileSpec.EndpointSpec endpoint) {
        return endpoint != null
                && ("sql".equalsIgnoreCase(endpoint.type) || "trino".equalsIgnoreCase(endpoint.type));
    }

    private boolean hasKey(TaskFileSpec spec) {
        return spec.object != null && spec.object.key != null && !spec.object.key.isEmpty();
    }

    private String firstSliceColumn(TaskFileSpec spec) {
        if (spec.object != null && spec.object.partitionBy != null && !spec.object.partitionBy.isEmpty()) {
            return spec.object.partitionBy.get(0);
        }
        return null;
    }

    private String connectorName(CapabilityDescriptor sourceCapability,
                                 CapabilityDescriptor targetCapability,
                                 String defaultName) {
        if (sourceCapability != null && sourceCapability.connectorType != null) {
            return sourceCapability.connectorType;
        }
        if (targetCapability != null && targetCapability.connectorType != null) {
            return targetCapability.connectorType;
        }
        return defaultName;
    }

    private List<String> levels(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }
}
