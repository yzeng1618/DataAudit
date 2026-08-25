// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ExactDiffEvidence;
import io.github.dataaudit.spi.model.ProgressEvent;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SamplingSummary;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ExactDiffExecutor {
    private static final int DEFAULT_SAMPLE_MODULO = 10;
    private static final String LIMIT_GLOBAL_TIMEOUT_MILLIS = "global_timeout_millis";

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
            evidence.progressEvents.add(ProgressEvent.started(taskName(spec), null, "exact_diff", null));
            evidence.diff = diffEngine.diff(sourceReader, targetReader, spec, null);
            recordLimit(evidence, evidence.diff, null);
            addLimitEventIfNeeded(evidence, spec, evidence.diff, null);
            evidence.progressEvents.add(ProgressEvent.completed(taskName(spec), null, "exact_diff", null));
            return evidence;
        }

        if (localization == null || localization.proofMode == null) {
            return evidence;
        }

        if (localization.proofMode == ProofMode.XOR_CHECKSUM_PLUS_SAMPLE
                || localization.proofMode == ProofMode.SAMPLING) {
            evidence.completed = true;
            evidence.progressEvents.add(ProgressEvent.started(taskName(spec), null, "exact_diff", null));
            evidence.diff = sampledDiff(spec, sourceReader, targetReader, null, evidence.samplingSummary);
            recordLimit(evidence, evidence.diff, null);
            addLimitEventIfNeeded(evidence, spec, evidence.diff, null);
            evidence.progressEvents.add(ProgressEvent.completed(taskName(spec), null, "exact_diff", null));
            return evidence;
        }

        if (localization.suspiciousScopes == null || localization.suspiciousScopes.isEmpty()) {
            return evidence;
        }

        return executeSuspectScopes(spec, sourceReader, targetReader, localization.suspiciousScopes);
    }

    public ExactDiffEvidence executeSuspectScopes(TaskFileSpec spec,
                                                  RowStreamReader sourceReader,
                                                  RowStreamReader targetReader,
                                                  List<SliceDescriptor> scopes) throws Exception {
        ExactDiffEvidence evidence = new ExactDiffEvidence();
        if (sourceReader == null || targetReader == null || scopes == null || scopes.isEmpty()) {
            return evidence;
        }

        evidence.completed = true;
        DiffResult merged = new DiffResult();
        int parallelism = segmentParallelism(spec, scopes.size());
        long deadlineNanos = deadlineNanos(spec);
        if (parallelism <= 1) {
            for (SliceDescriptor scope : scopes) {
                if (deadlineExceeded(deadlineNanos)) {
                    mergeOutcome(evidence, merged, timeoutOutcome(spec, scope == null ? null : scope.sliceKey), maxDiffSamples(spec));
                    break;
                }
                mergeOutcome(evidence, merged, executeScope(spec, sourceReader, targetReader, scope), maxDiffSamples(spec));
                if (deadlineExceeded(deadlineNanos)) {
                    mergeOutcome(evidence, merged, timeoutOutcome(spec, scope == null ? null : scope.sliceKey), maxDiffSamples(spec));
                    break;
                }
            }
        } else {
            executeScopesInParallel(spec, sourceReader, targetReader, scopes, evidence, merged, parallelism, deadlineNanos);
        }
        evidence.diff = merged;
        return evidence;
    }

    private void executeScopesInParallel(TaskFileSpec spec,
                                         RowStreamReader sourceReader,
                                         RowStreamReader targetReader,
                                         List<SliceDescriptor> scopes,
                                         ExactDiffEvidence evidence,
                                         DiffResult merged,
                                         int parallelism,
                                         long deadlineNanos) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<SegmentOutcome>> futures = new ArrayList<>();
            for (SliceDescriptor scope : scopes) {
                Callable<SegmentOutcome> task = () -> executeScope(spec, sourceReader, targetReader, scope);
                futures.add(executor.submit(task));
            }
            for (int index = 0; index < futures.size(); index++) {
                SliceDescriptor scope = scopes.get(index);
                if (deadlineExceeded(deadlineNanos)) {
                    mergeOutcome(evidence, merged, timeoutOutcome(spec, scope == null ? null : scope.sliceKey), maxDiffSamples(spec));
                    break;
                }
                try {
                    mergeOutcome(evidence, merged, getFuture(futures.get(index), deadlineNanos), maxDiffSamples(spec));
                } catch (ResourceTimeoutExceededException e) {
                    futures.get(index).cancel(true);
                    mergeOutcome(evidence, merged, timeoutOutcome(spec, scope == null ? null : scope.sliceKey), maxDiffSamples(spec));
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private SegmentOutcome getFuture(Future<SegmentOutcome> future, long deadlineNanos) throws Exception {
        try {
            if (deadlineNanos == Long.MAX_VALUE) {
                return future.get();
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new ResourceTimeoutExceededException();
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new ResourceTimeoutExceededException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private SegmentOutcome executeScope(TaskFileSpec spec,
                                        RowStreamReader sourceReader,
                                        RowStreamReader targetReader,
                                        SliceDescriptor scope) throws Exception {
        String sliceKey = scope == null ? null : scope.sliceKey;
        SegmentOutcome outcome = new SegmentOutcome();
        outcome.events.add(ProgressEvent.started(taskName(spec), null, "exact_diff", sliceKey));
        if (scope != null && scope.drilldownable) {
            outcome.diff = diffEngine.diff(sourceReader, targetReader, spec, sliceKey);
        } else {
            outcome.diff = sampledDiff(spec, sourceReader, targetReader, sliceKey, outcome.samplingSummary);
        }
        if (outcome.diff.limitExceeded) {
            outcome.events.add(ProgressEvent.limitExceeded(
                    taskName(spec),
                    null,
                    "exact_diff",
                    sliceKey,
                    outcome.diff.limitType,
                    outcome.diff.fallbackReason
            ));
        }
        outcome.events.add(ProgressEvent.completed(taskName(spec), null, "exact_diff", sliceKey));
        return outcome;
    }

    private SegmentOutcome timeoutOutcome(TaskFileSpec spec, String sliceKey) {
        SegmentOutcome outcome = new SegmentOutcome();
        outcome.diff = limitExceeded("exact_diff_resource_limit", LIMIT_GLOBAL_TIMEOUT_MILLIS);
        outcome.events.add(ProgressEvent.limitExceeded(
                taskName(spec),
                null,
                "exact_diff",
                sliceKey,
                LIMIT_GLOBAL_TIMEOUT_MILLIS,
                LIMIT_GLOBAL_TIMEOUT_MILLIS + "_exceeded"
        ));
        return outcome;
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

    private void mergeOutcome(ExactDiffEvidence evidence, DiffResult merged, SegmentOutcome outcome, int sampleLimit) {
        evidence.progressEvents.addAll(outcome.events);
        if (outcome.samplingSummary.used) {
            mergeSamplingSummary(evidence.samplingSummary, outcome.samplingSummary);
        }
        recordLimit(evidence, outcome.diff, outcome.sliceKey());
        mergeDiff(merged, outcome.diff, sampleLimit);
    }

    private void recordLimit(ExactDiffEvidence evidence, DiffResult diff, String sliceKey) {
        if (diff == null || !diff.limitExceeded) {
            return;
        }
        evidence.limitExceeded = true;
        evidence.limitType = evidence.limitType == null ? diff.limitType : evidence.limitType;
    }

    private void addLimitEventIfNeeded(ExactDiffEvidence evidence,
                                       TaskFileSpec spec,
                                       DiffResult diff,
                                       String sliceKey) {
        if (diff == null || !diff.limitExceeded) {
            return;
        }
        evidence.progressEvents.add(ProgressEvent.limitExceeded(
                taskName(spec),
                null,
                "exact_diff",
                sliceKey,
                diff.limitType,
                diff.fallbackReason
        ));
    }

    private void mergeSamplingSummary(SamplingSummary target, SamplingSummary partial) {
        target.used = target.used || partial.used;
        target.mode = target.mode == null ? partial.mode : target.mode;
        target.sampleColumn = target.sampleColumn == null ? partial.sampleColumn : target.sampleColumn;
        target.sampleModulo = target.sampleModulo == null ? partial.sampleModulo : target.sampleModulo;
        target.sampleRemainder = target.sampleRemainder == null ? partial.sampleRemainder : target.sampleRemainder;
        target.sourceRows = nullToZero(target.sourceRows) + nullToZero(partial.sourceRows);
        target.targetRows = nullToZero(target.targetRows) + nullToZero(partial.targetRows);
    }

    private void mergeDiff(DiffResult merged, DiffResult partial, int sampleLimit) {
        if (partial == null) {
            return;
        }
        merged.consistent = merged.consistent && partial.consistent;
        merged.sampled = merged.sampled || partial.sampled;
        merged.resourceBounded = merged.resourceBounded || partial.resourceBounded;
        merged.limitExceeded = merged.limitExceeded || partial.limitExceeded;
        merged.limitType = merged.limitType == null ? partial.limitType : merged.limitType;
        merged.fallbackReason = merged.fallbackReason == null ? partial.fallbackReason : merged.fallbackReason;
        for (DiffResult.DiffSample sample : partial.samples) {
            if (merged.samples.size() >= sampleLimit) {
                break;
            }
            merged.samples.add(sample);
        }
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

    private int segmentParallelism(TaskFileSpec spec, int scopeCount) {
        int configured = spec == null || spec.resources == null || spec.resources.segmentParallelism == null
                ? 1
                : spec.resources.segmentParallelism;
        return Math.max(1, Math.min(configured, scopeCount));
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private int maxDiffSamples(TaskFileSpec spec) {
        if (spec == null || spec.resources == null || spec.resources.maxDiffSamples == null) {
            return 500;
        }
        return spec.resources.maxDiffSamples;
    }

    private long deadlineNanos(TaskFileSpec spec) {
        long timeoutMillis = spec == null || spec.resources == null || spec.resources.globalTimeoutMillis == null
                ? 0L
                : spec.resources.globalTimeoutMillis;
        if (timeoutMillis <= 0L) {
            return Long.MAX_VALUE;
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long now = System.nanoTime();
        if (Long.MAX_VALUE - now < timeoutNanos) {
            return Long.MAX_VALUE;
        }
        return now + timeoutNanos;
    }

    private boolean deadlineExceeded(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    private DiffResult limitExceeded(String rootCause, String limitType) {
        DiffResult result = new DiffResult();
        result.consistent = false;
        result.sampled = true;
        result.resourceBounded = true;
        result.limitExceeded = true;
        result.limitType = limitType;
        result.fallbackReason = limitType + "_exceeded";
        result.rootCause = rootCause;
        return result;
    }

    private String taskName(TaskFileSpec spec) {
        return spec == null || spec.task == null ? null : spec.task.name;
    }

    private static final class SegmentOutcome {
        private DiffResult diff = new DiffResult();
        private SamplingSummary samplingSummary = new SamplingSummary();
        private List<ProgressEvent> events = new ArrayList<>();

        private String sliceKey() {
            for (ProgressEvent event : events) {
                if (event != null && event.sliceKey != null) {
                    return event.sliceKey;
                }
            }
            return null;
        }
    }

    private static final class ResourceTimeoutExceededException extends Exception {
    }
}
