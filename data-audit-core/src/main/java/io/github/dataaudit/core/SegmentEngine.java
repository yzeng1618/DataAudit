package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.DataReader;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SegmentDescriptor;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SegmentEngine {
    private final SummaryEngine summaryEngine;

    public SegmentEngine(SummaryEngine summaryEngine) {
        this.summaryEngine = summaryEngine;
    }

    public List<SegmentDescriptor> findSuspectSegments(DataReader source,
                                                       DataReader target,
                                                       TaskFileSpec spec) throws Exception {
        String segmentColumn = resolveSegmentColumn(spec);
        if (segmentColumn == null) {
            return new ArrayList<>();
        }

        ReadRequest baseRequest = new ReadRequest();
        Set<String> values = new LinkedHashSet<>();
        values.addAll(source.listSegmentValues(segmentColumn, baseRequest));
        values.addAll(target.listSegmentValues(segmentColumn, baseRequest));

        List<SegmentDescriptor> suspects = new ArrayList<>();
        for (String value : values) {
            ReadRequest request = new ReadRequest();
            request.segmentColumn = segmentColumn;
            request.segmentValue = value;
            SummaryMetrics sourceSummary = summaryEngine.summarize(source, spec, request);
            SummaryMetrics targetSummary = summaryEngine.summarize(target, spec, request);
            if (!summaryEngine.equivalent(sourceSummary, targetSummary)) {
                SegmentDescriptor descriptor = new SegmentDescriptor();
                descriptor.segmentColumn = segmentColumn;
                descriptor.segmentValue = value;
                descriptor.segmentKey = segmentColumn + "=" + value;
                descriptor.reason = "summary_mismatch";
                descriptor.sourceDigest = sourceSummary.checksum;
                descriptor.targetDigest = targetSummary.checksum;
                suspects.add(descriptor);
            }
        }
        return suspects;
    }

    private String resolveSegmentColumn(TaskFileSpec spec) {
        if (spec.compare != null && spec.compare.segment != null && spec.compare.segment.by != null && !spec.compare.segment.by.isEmpty()) {
            return spec.compare.segment.by.get(0);
        }
        if (spec.planner != null && spec.planner.hints != null && spec.planner.hints.partitionKeys != null && !spec.planner.hints.partitionKeys.isEmpty()) {
            return spec.planner.hints.partitionKeys.get(0);
        }
        return null;
    }
}

