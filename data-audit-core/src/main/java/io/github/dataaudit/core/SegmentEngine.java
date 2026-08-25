// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.RoutingSignalReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SegmentEngine {
    private static final long MAX_EXACT_ROWS = 100_000L;
    static final int DEFAULT_BUCKET_COUNT = 16;
    static final String VIRTUAL_BUCKET_PREFIX = "bucket=";

    private final SummaryEngine summaryEngine;

    public SegmentEngine() {
        this(new SummaryEngine(new NormalizationService(), new HashProvider()));
    }

    public SegmentEngine(SummaryEngine summaryEngine) {
        this.summaryEngine = summaryEngine;
    }

    public List<SliceDescriptor> findSuspectSlices(SignalReader source,
                                                   SignalReader target,
                                                   TaskFileSpec spec) throws Exception {
        String sliceColumn = resolveSliceColumn(spec);
        if (sliceColumn == null) {
            return new ArrayList<>();
        }

        ReadRequest baseRequest = ReadRequestFactory.baseRequest(spec);
        return findSignalMismatches(
                source.readSliceSignals(sliceColumn, baseRequest),
                target.readSliceSignals(sliceColumn, baseRequest)
        );
    }

    public List<SliceDescriptor> findSuspectRoutingSignals(RoutingSignalReader source,
                                                           RoutingSignalReader target,
                                                           TaskFileSpec spec) throws Exception {
        ReadRequest baseRequest = ReadRequestFactory.baseRequest(spec);
        return findSignalMismatches(
                source.readRoutingSignals(baseRequest),
                target.readRoutingSignals(baseRequest)
        );
    }

    public String resolveSliceColumn(TaskFileSpec spec) {
        if (spec.object != null && spec.object.partitionBy != null && !spec.object.partitionBy.isEmpty()) {
            return spec.object.partitionBy.get(0);
        }
        return null;
    }

    public List<SliceDescriptor> findSuspectBuckets(RowStreamReader source,
                                                    RowStreamReader target,
                                                    TaskFileSpec spec) throws Exception {
        if (!hasKey(spec)) {
            return new ArrayList<>();
        }

        int bucketCount = resolveBucketCount(spec);
        String bucketColumn = spec.object.key.get(0);
        List<SliceDescriptor> suspects = new ArrayList<>();
        for (int bucketId = 0; bucketId < bucketCount; bucketId++) {
            SummaryMetrics sourceSummary = summaryEngine.summarize(
                    source,
                    spec,
                    ReadRequestFactory.bucketRequest(spec, bucketColumn, bucketCount, bucketId)
            );
            SummaryMetrics targetSummary = summaryEngine.summarize(
                    target,
                    spec,
                    ReadRequestFactory.bucketRequest(spec, bucketColumn, bucketCount, bucketId)
            );
            if (!summaryEngine.equivalent(sourceSummary, targetSummary)) {
                SliceDescriptor descriptor = new SliceDescriptor();
                descriptor.sliceKey = VIRTUAL_BUCKET_PREFIX + bucketId + "/" + bucketCount;
                descriptor.sliceType = "key_hash_bucket";
                descriptor.rowEstimate = Math.max(sourceSummary.rowCount, targetSummary.rowCount);
                descriptor.drilldownable = descriptor.rowEstimate <= MAX_EXACT_ROWS;
                descriptor.reason = "bucket_signal_mismatch";
                suspects.add(descriptor);
            }
        }
        return suspects;
    }

    private boolean equivalent(SliceSignal source, SliceSignal target) {
        if (source == null || target == null) {
            return false;
        }
        return source.rowCount == target.rowCount
                && (source.checksum == null ? target.checksum == null : source.checksum.equals(target.checksum));
    }

    private SliceDescriptor toDescriptor(SliceSignal source, SliceSignal target) {
        SliceDescriptor descriptor = new SliceDescriptor();
        descriptor.sliceKey = source != null ? source.sliceKey : target.sliceKey;
        descriptor.sliceType = source != null ? source.sliceType : target.sliceType;
        long rowEstimate = Math.max(source == null ? 0L : source.rowCount, target == null ? 0L : target.rowCount);
        descriptor.rowEstimate = rowEstimate;
        descriptor.drilldownable = rowEstimate <= MAX_EXACT_ROWS;
        descriptor.reason = "signal_mismatch";
        return descriptor;
    }

    private List<SliceDescriptor> findSignalMismatches(List<SliceSignal> sourceSignals,
                                                       List<SliceSignal> targetSignals) {
        Map<String, SliceSignal> merged = new LinkedHashMap<>();
        for (SliceSignal signal : sourceSignals) {
            merged.put(signal.sliceKey, signal);
        }

        List<SliceDescriptor> suspects = new ArrayList<>();
        for (SliceSignal targetSignal : targetSignals) {
            SliceSignal sourceSignal = merged.remove(targetSignal.sliceKey);
            if (!equivalent(sourceSignal, targetSignal)) {
                suspects.add(toDescriptor(sourceSignal, targetSignal));
            }
        }

        for (SliceSignal sourceSignal : merged.values()) {
            suspects.add(toDescriptor(sourceSignal, null));
        }
        return suspects;
    }

    private boolean hasKey(TaskFileSpec spec) {
        return spec.object != null && spec.object.key != null && !spec.object.key.isEmpty();
    }

    private int resolveBucketCount(TaskFileSpec spec) {
        long estimatedRows = spec.object == null || spec.object.estimatedRows == null ? -1L : spec.object.estimatedRows;
        if (estimatedRows < 0L) {
            return DEFAULT_BUCKET_COUNT;
        }
        long bucketCount = (estimatedRows + MAX_EXACT_ROWS - 1L) / MAX_EXACT_ROWS;
        bucketCount = Math.max(DEFAULT_BUCKET_COUNT, bucketCount);
        bucketCount = Math.min(256L, bucketCount);
        return (int) bucketCount;
    }
}
