package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.RoutingSignalReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.LocalizationEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.List;

public class LocalizationEngine {
    private final SegmentEngine segmentEngine;
    private final XorChecksumEngine xorChecksumEngine;
    private final SamplingEngine samplingEngine;

    public LocalizationEngine() {
        this(new SegmentEngine(), new XorChecksumEngine(), new SamplingEngine());
    }

    LocalizationEngine(SegmentEngine segmentEngine, XorChecksumEngine xorChecksumEngine, SamplingEngine samplingEngine) {
        this.segmentEngine = segmentEngine;
        this.xorChecksumEngine = xorChecksumEngine;
        this.samplingEngine = samplingEngine;
    }

    public LocalizationEvidence localize(TaskFileSpec spec,
                                         ScaleClass scaleClass,
                                         SignalReader sourceSignalReader,
                                         SignalReader targetSignalReader,
                                         RoutingSignalReader sourceRoutingReader,
                                         RoutingSignalReader targetRoutingReader,
                                         RowStreamReader sourceRowReader,
                                         RowStreamReader targetRowReader) throws Exception {
        LocalizationEvidence evidence = new LocalizationEvidence();
        if (scaleClass == ScaleClass.SMALL) {
            evidence.strategy = "none";
            evidence.proofMode = ProofMode.EXACT_DIFF;
            evidence.confidence = ConfidenceLevel.EXACT;
            return evidence;
        }

        if (scaleClass == ScaleClass.XLARGE) {
            return localizeXLarge(spec, sourceRoutingReader, targetRoutingReader, sourceRowReader, targetRowReader);
        }

        String sliceColumn = resolveSliceColumn(spec);
        if (sliceColumn != null) {
            List<SliceDescriptor> suspects = segmentEngine.findSuspectSlices(sourceSignalReader, targetSignalReader, spec);
            evidence.strategy = "partition_window";
            evidence.proofMode = ProofMode.GROUPED_CHECKSUM;
            evidence.confidence = ConfidenceLevel.HIGH;
            evidence.suspiciousScopes.addAll(suspects);
            return evidence;
        }

        if (spec.object != null && spec.object.groupBy != null && !spec.object.groupBy.isEmpty()) {
            TaskFileSpec groupedSpec = copyWithPartitionBy(spec, spec.object.groupBy.get(0));
            List<SliceDescriptor> suspects = segmentEngine.findSuspectSlices(sourceSignalReader, targetSignalReader, groupedSpec);
            evidence.strategy = "group_by";
            evidence.proofMode = ProofMode.GROUPED_CHECKSUM;
            evidence.confidence = ConfidenceLevel.HIGH;
            evidence.suspiciousScopes.addAll(suspects);
            return evidence;
        }

        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            List<SliceDescriptor> suspects = segmentEngine.findSuspectBuckets(sourceRowReader, targetRowReader, spec);
            evidence.strategy = "key_hash_bucket";
            evidence.proofMode = ProofMode.GROUPED_CHECKSUM;
            evidence.confidence = ConfidenceLevel.HIGH;
            evidence.suspiciousScopes.addAll(suspects);
            return evidence;
        }

        evidence.strategy = "no_key_xor";
        evidence.proofMode = ProofMode.XOR_CHECKSUM_PLUS_SAMPLE;
        evidence.confidence = ConfidenceLevel.MEDIUM;
        evidence.noKeyMode = true;
        evidence.fallbackReason = "no_key_xor_fallback";
        String sourceChecksum = xorChecksumEngine.checksum(spec, sourceRowReader);
        String targetChecksum = xorChecksumEngine.checksum(spec, targetRowReader);
        if (!sourceChecksum.equals(targetChecksum)) {
            SliceDescriptor descriptor = new SliceDescriptor();
            descriptor.sliceKey = "full_table";
            descriptor.sliceType = "no_key_xor";
            descriptor.reason = "xor_checksum_mismatch";
            evidence.suspiciousScopes.add(descriptor);
        }
        return evidence;
    }

    private LocalizationEvidence localizeXLarge(TaskFileSpec spec,
                                                RoutingSignalReader sourceRoutingReader,
                                                RoutingSignalReader targetRoutingReader,
                                                RowStreamReader sourceRowReader,
                                                RowStreamReader targetRowReader) throws Exception {
        LocalizationEvidence evidence = new LocalizationEvidence();
        if (sourceRoutingReader != null && targetRoutingReader != null) {
            evidence.strategy = "routing_digest";
            evidence.proofMode = ProofMode.ROUTING_DIGEST;
            evidence.confidence = ConfidenceLevel.HIGH;
            evidence.suspiciousScopes.addAll(segmentEngine.findSuspectRoutingSignals(sourceRoutingReader, targetRoutingReader, spec));
            return evidence;
        }

        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            evidence.strategy = "key_hash_bucket";
            evidence.proofMode = ProofMode.GROUPED_CHECKSUM;
            evidence.confidence = ConfidenceLevel.HIGH;
            evidence.suspiciousScopes.addAll(segmentEngine.findSuspectBuckets(sourceRowReader, targetRowReader, spec));
            return evidence;
        }

        return samplingEngine.proportionalSample(spec, sourceRowReader, targetRowReader);
    }

    private String resolveSliceColumn(TaskFileSpec spec) {
        if (spec.object != null && spec.object.partitionBy != null && !spec.object.partitionBy.isEmpty()) {
            return spec.object.partitionBy.get(0);
        }
        return null;
    }

    private TaskFileSpec copyWithPartitionBy(TaskFileSpec spec, String sliceColumn) {
        TaskFileSpec copy = new TaskFileSpec();
        copy.task = spec.task;
        copy.boundary = spec.boundary;
        copy.planner = spec.planner;
        copy.queryConnector = spec.queryConnector;
        copy.source = spec.source;
        copy.target = spec.target;
        copy.object = new TaskFileSpec.ObjectSpec();
        copy.object.key.addAll(spec.object.key);
        copy.object.columns.addAll(spec.object.columns);
        copy.object.groupBy.addAll(spec.object.groupBy);
        copy.object.estimatedRows = spec.object.estimatedRows;
        copy.object.estimatedBytes = spec.object.estimatedBytes;
        copy.object.routingStrategy = spec.object.routingStrategy;
        copy.normalize = spec.normalize;
        copy.semantics = spec.semantics;
        copy.output = spec.output;
        copy.object.partitionBy = new java.util.ArrayList<>();
        copy.object.partitionBy.add(sliceColumn);
        return copy;
    }
}
