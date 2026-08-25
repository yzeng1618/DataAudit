// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;

public class PlanningService {
    private final ScaleClassifier scaleClassifier = new ScaleClassifier();

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
        ScaleClass scaleClass = scaleClassifier.classify(spec);
        boolean trinoPreferred = hasTrinoEndpoint(spec)
                && spec.queryConnector != null
                && "trino".equalsIgnoreCase(spec.queryConnector.type);
        boolean metadataCapable = supportsMetadataStats(sourceCapability) || supportsMetadataStats(targetCapability);
        boolean routingCapable = supportsRoutingSignal(sourceCapability) || supportsRoutingSignal(targetCapability);

        plan.decisionTrace.add("estimated rows: " + (estimatedRows < 0 ? "unknown" : estimatedRows));
        plan.decisionTrace.add("trino preferred: " + trinoPreferred);
        plan.decisionTrace.add("metadata capable: " + metadataCapable);
        plan.decisionTrace.add("routing capable: " + routingCapable);
        plan.decisionTrace.add("localization hint: " + resolveLocalizationHint(spec));

        if (scaleClass == ScaleClass.SMALL) {
            plan.scaleClass = ScaleClass.SMALL;
            plan.signalStrategy = "global_row_count_plus_checksum";
            plan.proofMode = ProofMode.GLOBAL_CHECKSUM;
            plan.localizationStrategy = "none";
            plan.reason = "small table uses global checksum gate";
            plan.decisionTrace.add("selected global checksum gate because scale classified as SMALL");
            return plan;
        }

        plan.scaleClass = scaleClass;
        if (scaleClass == ScaleClass.XLARGE) {
            plan.signalStrategy = metadataCapable ? "partition_stats_or_metadata" : "routing_digest_or_sampling";
            plan.localizationStrategy = resolveXLargeLocalizationStrategy(spec, routingCapable);
            plan.proofMode = resolveXLargeProofMode(spec, routingCapable);
            plan.reason = "xlarge table uses routing or sampling fallback";
            return plan;
        }

        plan.signalStrategy = "global_row_count_plus_grouped_checksum";
        plan.localizationStrategy = resolveLargeLocalizationStrategy(spec);
        plan.proofMode = "no_key_xor".equals(plan.localizationStrategy)
                ? ProofMode.XOR_CHECKSUM_PLUS_SAMPLE
                : ProofMode.GROUPED_CHECKSUM;
        plan.reason = "large table uses grouped checksum localization";
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

    private String resolveLargeLocalizationStrategy(TaskFileSpec spec) {
        if (firstSliceColumn(spec) != null) {
            return "partition_window";
        }
        if (spec.object != null && spec.object.groupBy != null && !spec.object.groupBy.isEmpty()) {
            return "group_by";
        }
        if (hasKey(spec)) {
            return "key_hash_bucket";
        }
        return "no_key_xor";
    }

    private String resolveXLargeLocalizationStrategy(TaskFileSpec spec, boolean routingCapable) {
        if (routingCapable) {
            return "routing_digest";
        }
        if (hasKey(spec)) {
            return "key_hash_bucket";
        }
        return "proportional_sampling";
    }

    private ProofMode resolveXLargeProofMode(TaskFileSpec spec, boolean routingCapable) {
        if (routingCapable) {
            return ProofMode.ROUTING_DIGEST;
        }
        if (hasKey(spec)) {
            return ProofMode.GROUPED_CHECKSUM;
        }
        return ProofMode.SAMPLING;
    }

    private String resolveLocalizationHint(TaskFileSpec spec) {
        if (firstSliceColumn(spec) != null) {
            return "partition_window";
        }
        if (spec.object != null && spec.object.groupBy != null && !spec.object.groupBy.isEmpty()) {
            return "group_by";
        }
        if (hasKey(spec)) {
            return "key_hash_bucket";
        }
        return "no_key";
    }

    private boolean supportsMetadataStats(CapabilityDescriptor capabilityDescriptor) {
        return capabilityDescriptor != null && capabilityDescriptor.supportsMetadataStats;
    }

    private boolean supportsRoutingSignal(CapabilityDescriptor capabilityDescriptor) {
        return capabilityDescriptor != null && capabilityDescriptor.supportsRoutingSignalPushdown;
    }
}
