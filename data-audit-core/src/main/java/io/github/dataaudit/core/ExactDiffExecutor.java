package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ExactDiffEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SamplingSummary;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class ExactDiffExecutor {
    private static final int DEFAULT_SAMPLE_MODULO = 10;

    private final DiffEngine diffEngine;

    public ExactDiffExecutor(DiffEngine diffEngine) {
        this.diffEngine = diffEngine;
    }

    public ExactDiffEvidence execute(TaskFileSpec spec,
                                     ScaleClass scaleClass,
                                     RowStreamReader sourceReader,
                                     RowStreamReader targetReader,
                                     io.github.dataaudit.spi.model.LocalizationEvidence localization) throws Exception {
        ExactDiffEvidence evidence = new ExactDiffEvidence();
        if (sourceReader == null || targetReader == null) {
            return evidence;
        }

        if (scaleClass == ScaleClass.SMALL) {
            if (localization != null && localization.proofMode == ProofMode.GLOBAL_CHECKSUM) {
                return evidence;
            }
            evidence.completed = true;
            evidence.diff = diffEngine.diff(sourceReader, targetReader, spec, null);
            return evidence;
        }

        if (localization == null || localization.proofMode == null) {
            return evidence;
        }

        if (localization.proofMode == ProofMode.XOR_CHECKSUM_PLUS_SAMPLE
                || localization.proofMode == ProofMode.SAMPLING) {
            evidence.completed = true;
            evidence.diff = sampledDiff(spec, sourceReader, targetReader, null, evidence.samplingSummary);
            return evidence;
        }

        if (localization.suspiciousScopes == null || localization.suspiciousScopes.isEmpty()) {
            return evidence;
        }

        evidence.completed = true;
        DiffResult merged = new DiffResult();
        for (SliceDescriptor scope : localization.suspiciousScopes) {
            DiffResult partial = scope != null && scope.drilldownable
                    ? diffEngine.diff(sourceReader, targetReader, spec, scope.sliceKey)
                    : sampledDiff(spec, sourceReader, targetReader, scope == null ? null : scope.sliceKey, evidence.samplingSummary);
            mergeDiff(merged, partial);
        }
        evidence.diff = merged;
        return evidence;
    }

    private DiffResult sampledDiff(TaskFileSpec spec,
                                   RowStreamReader sourceReader,
                                   RowStreamReader targetReader,
                                   String sliceKey,
                                   SamplingSummary samplingSummary) throws Exception {
        String sampleColumn = chooseSampleColumn(spec);
        ReadRequest sourceRequest = requestForSample(spec, sampleColumn, sliceKey);
        ReadRequest targetRequest = requestForSample(spec, sampleColumn, sliceKey);
        samplingSummary.used = true;
        samplingSummary.mode = "deterministic_hash_mod";
        samplingSummary.sampleColumn = sampleColumn;
        samplingSummary.sampleModulo = DEFAULT_SAMPLE_MODULO;
        samplingSummary.sampleRemainder = 0;
        DiffResult sampled = diffEngine.diffRows(
                collectRows(sourceReader, sourceRequest, samplingSummary, true),
                collectRows(targetReader, targetRequest, samplingSummary, false),
                spec,
                sliceKey
        );
        sampled.sampled = true;
        return sampled;
    }

    private java.util.List<java.util.Map<String, Object>> collectRows(RowStreamReader reader,
                                                                      ReadRequest request,
                                                                      SamplingSummary samplingSummary,
                                                                      boolean source) throws Exception {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        reader.scanRows(request, rows::add);
        if (source) {
            samplingSummary.sourceRows = (long) rows.size();
        } else {
            samplingSummary.targetRows = (long) rows.size();
        }
        return rows;
    }

    private void mergeDiff(DiffResult merged, DiffResult partial) {
        merged.consistent = merged.consistent && partial.consistent;
        merged.sampled = merged.sampled || partial.sampled;
        merged.samples.addAll(partial.samples);
        if (!partial.consistent) {
            merged.rootCause = partial.rootCause;
        }
    }

    private ReadRequest requestForSample(TaskFileSpec spec, String sampleColumn, String sliceKey) {
        int[] bucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        if (bucket != null && spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return ReadRequestFactory.bucketRequest(spec, sampleColumn, bucket[1], bucket[0]);
        }
        String sliceColumn = null;
        String sliceValue = null;
        if (sliceKey != null && sliceKey.contains("=")) {
            String[] parts = sliceKey.split("=", 2);
            sliceColumn = parts[0];
            sliceValue = parts[1];
        }
        return ReadRequestFactory.sampleRequest(spec, sampleColumn, DEFAULT_SAMPLE_MODULO, 0, sliceColumn, sliceValue);
    }

    private String chooseSampleColumn(TaskFileSpec spec) {
        if (spec.object != null && spec.object.key != null && !spec.object.key.isEmpty()) {
            return spec.object.key.get(0);
        }
        if (spec.object != null && spec.object.columns != null && !spec.object.columns.isEmpty()) {
            return spec.object.columns.get(0);
        }
        return "row_digest";
    }
}
