package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlanningService {
    public ExecutionPlan plan(TaskFileSpec spec,
                              CapabilityDescriptor sourceCapability,
                              CapabilityDescriptor targetCapability,
                              BoundaryRef boundaryRef) {
        ExecutionPlan plan = new ExecutionPlan();
        plan.boundary = boundaryRef;
        if (!boundaryRef.stable) {
            plan.refuseReason = "unstable_boundary";
            plan.reason = boundaryRef.detail;
            return plan;
        }

        boolean preferMetadata = spec.planner.hints.preferMetadata == null || spec.planner.hints.preferMetadata;
        boolean forceExactDiff = spec.planner.hints.forceExactDiff != null && spec.planner.hints.forceExactDiff;
        long maxExactRows = spec.planner.hints.maxExactRows == null ? 100_000L : spec.planner.hints.maxExactRows;
        Long estimatedRows = spec.planner.hints.estimatedRows;
        String mode = normalizeMode(spec);
        boolean hasMetadata = (sourceCapability != null && sourceCapability.supportsSnapshotBoundary)
                || (targetCapability != null && targetCapability.supportsSnapshotBoundary)
                || (sourceCapability != null && sourceCapability.supportsMetadataStats)
                || (targetCapability != null && targetCapability.supportsMetadataStats);
        boolean isLakehouse = "lakehouse_object".equalsIgnoreCase(spec.planner.hints.objectClass)
                || "iceberg".equalsIgnoreCase(spec.source.type)
                || "iceberg".equalsIgnoreCase(spec.target.type)
                || "snapshot".equalsIgnoreCase(spec.boundary.type);

        if ("metadata_first".equals(mode) && hasMetadata) {
            return metadataFirstPlan(plan, spec, "metadata_first mode requested");
        }

        if (isLakehouse && preferMetadata && hasMetadata) {
            plan.objectClass = "lakehouse_object";
            plan.selectedPath = "boundary metadata -> schema -> summary -> segment -> diff";
            plan.executedLevels = levels("metadata", "schema", "summary", "segment", "diff");
            plan.segmentStrategy = firstSegmentColumn(spec);
            plan.resumeStrategy = "suspect_segment";
            plan.reason = "metadata capability available and boundary is snapshot-aware";
            return plan;
        }

        if (forceExactDiff || "exact_first".equals(mode) || (estimatedRows != null && estimatedRows <= maxExactRows && !"segment_first".equals(mode))) {
            plan.objectClass = "small_table_once";
            plan.selectedPath = "schema -> exact diff";
            plan.executedLevels = levels("schema", "diff");
            plan.segmentStrategy = "none";
            plan.resumeStrategy = "rerun";
            if (forceExactDiff) {
                plan.shortCircuitReason = "forced_exact_diff";
            } else if ("exact_first".equals(mode)) {
                plan.shortCircuitReason = "exact_first_mode";
            } else {
                plan.shortCircuitReason = "estimated_rows_within_threshold";
            }
            plan.reason = plan.shortCircuitReason;
            return plan;
        }

        plan.objectClass = "partitioned_big_table";
        plan.selectedPath = "schema -> summary -> segment -> diff";
        plan.executedLevels = levels("schema", "summary", "segment", "diff");
        plan.segmentStrategy = firstSegmentColumn(spec);
        plan.resumeStrategy = "suspect_segment";
        plan.reason = "default segment-first path for large or partitioned object";
        return plan;
    }

    private List<String> levels(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private ExecutionPlan metadataFirstPlan(ExecutionPlan plan, TaskFileSpec spec, String reason) {
        plan.objectClass = "lakehouse_object";
        plan.selectedPath = "boundary metadata -> schema -> summary -> segment -> diff";
        plan.executedLevels = levels("metadata", "schema", "summary", "segment", "diff");
        plan.segmentStrategy = firstSegmentColumn(spec);
        plan.resumeStrategy = "suspect_segment";
        plan.reason = reason;
        return plan;
    }

    private String normalizeMode(TaskFileSpec spec) {
        if (spec == null || spec.planner == null || spec.planner.mode == null) {
            return "auto";
        }
        return spec.planner.mode.toLowerCase(Locale.ROOT);
    }

    private String firstSegmentColumn(TaskFileSpec spec) {
        if (spec.compare != null && spec.compare.segment != null && spec.compare.segment.by != null && !spec.compare.segment.by.isEmpty()) {
            return spec.compare.segment.by.get(0);
        }
        if (spec.planner != null && spec.planner.hints != null && spec.planner.hints.partitionKeys != null && !spec.planner.hints.partitionKeys.isEmpty()) {
            return spec.planner.hints.partitionKeys.get(0);
        }
        return "none";
    }
}
