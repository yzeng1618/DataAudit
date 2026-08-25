// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ExactDiffEvidence;
import io.github.dataaudit.spi.model.LocalizationEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ProgressEvent;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGovernanceTest {
    @Test
    void shouldExposeSafeResourceDefaultsOnNewTaskSpec() {
        TaskFileSpec spec = new TaskFileSpec();

        assertEquals(100_000L, spec.resources.maxInMemoryRows);
        assertEquals(500, spec.resources.maxDiffSamples);
        assertEquals(0L, spec.resources.globalTimeoutMillis);
        assertEquals(0L, spec.resources.queryTimeoutMillis);
        assertEquals(1, spec.resources.segmentParallelism);
    }

    @Test
    void shouldValidateResourceGovernanceSettings() {
        TaskFileSpec spec = validSpec();
        spec.resources.maxInMemoryRows = 0L;
        spec.resources.maxDiffSamples = 0;
        spec.resources.globalTimeoutMillis = -1L;
        spec.resources.queryTimeoutMillis = -1L;
        spec.resources.segmentParallelism = 0;

        List<String> issues = new SpecValidator().validate(spec);

        assertTrue(issues.contains("resources.max_in_memory_rows must be positive"));
        assertTrue(issues.contains("resources.max_diff_samples must be positive"));
        assertTrue(issues.contains("resources.global_timeout_millis must be non-negative"));
        assertTrue(issues.contains("resources.query_timeout_millis must be non-negative"));
        assertTrue(issues.contains("resources.segment_parallelism must be at least 1"));
    }

    @Test
    void shouldBoundKeyedDiffAndRespectSampleLimit() throws Exception {
        TaskFileSpec spec = validSpec();
        spec.object.key.add("id");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.resources.maxInMemoryRows = 4L;
        spec.resources.maxDiffSamples = 2;

        DiffResult result = new DiffEngine(new NormalizationService())
                .diff(rowReader(rows("A", 12)), rowReader(rows("B", 12)), spec, "dt=2026-03-10");

        assertFalse(result.consistent);
        assertTrue(result.resourceBounded);
        assertFalse(result.limitExceeded);
        assertEquals(2, result.samples.size());
        assertTrue(result.samples.stream().allMatch(sample -> "dt=2026-03-10".equals(sample.sliceKey)));
    }

    @Test
    void shouldFallbackForKeylessDiffWhenMemoryLimitIsExceeded() throws Exception {
        TaskFileSpec spec = validSpec();
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.resources.maxInMemoryRows = 3L;

        DiffResult result = new DiffEngine(new NormalizationService())
                .diff(rowReader(rows("A", 5)), rowReader(rows("A", 5)), spec, null);

        assertFalse(result.consistent);
        assertTrue(result.limitExceeded);
        assertEquals("max_in_memory_rows", result.limitType);
        assertEquals("keyless_diff_resource_limit", result.rootCause);
        assertTrue(result.samples.isEmpty());
    }

    @Test
    void shouldEmitProgressEventsAndLimitEvidenceForExactDiff() throws Exception {
        TaskFileSpec spec = validSpec();
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 1_000_000L;
        spec.resources.maxInMemoryRows = 3L;

        LocalizationEvidence localization = new LocalizationEvidence();
        localization.proofMode = ProofMode.GROUPED_CHECKSUM;
        SliceDescriptor scope = new SliceDescriptor();
        scope.sliceKey = "dt=2026-03-10";
        scope.drilldownable = true;
        localization.suspiciousScopes.add(scope);

        ExactDiffEvidence evidence = new ExactDiffExecutor(new DiffEngine(new NormalizationService()))
                .execute(spec, ScaleClass.LARGE, rowReader(rows("A", 5)), rowReader(rows("A", 5)), localization);

        assertTrue(evidence.completed);
        assertTrue(evidence.limitExceeded);
        assertEquals("max_in_memory_rows", evidence.limitType);
        assertTrue(evidence.progressEvents.stream().anyMatch(event ->
                "exact_diff".equals(event.stage) && "started".equals(event.status)));
        assertTrue(evidence.progressEvents.stream().anyMatch(event ->
                "exact_diff".equals(event.stage) && "limit_exceeded".equals(event.status)));
    }

    @Test
    void shouldDefaultSegmentDiffExecutionToSerialAndRespectConfiguredParallelism() throws Exception {
        TaskFileSpec spec = validSpec();
        spec.object.key.add("id");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.resources.segmentParallelism = 2;
        spec.resources.maxInMemoryRows = 100L;

        List<SliceDescriptor> scopes = List.of(scope("dt=2026-03-10"), scope("dt=2026-03-11"), scope("dt=2026-03-12"));
        TrackingDiffEngine diffEngine = new TrackingDiffEngine();

        ExactDiffEvidence evidence = new ExactDiffExecutor(diffEngine)
                .executeSuspectScopes(spec, rowReader(rows("A", 1)), rowReader(rows("A", 1)), scopes);

        assertTrue(evidence.completed);
        assertEquals(2, diffEngine.maxConcurrent.get());
        assertTrue(evidence.progressEvents.stream().anyMatch(event ->
                "exact_diff".equals(event.stage) && "completed".equals(event.status)));
    }

    @Test
    void shouldStopSegmentDiffWhenGlobalTimeoutIsExceeded() throws Exception {
        TaskFileSpec spec = validSpec();
        spec.object.key.add("id");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.resources.globalTimeoutMillis = 1L;
        spec.resources.segmentParallelism = 1;

        List<SliceDescriptor> scopes = List.of(scope("dt=2026-03-10"), scope("dt=2026-03-11"));

        ExactDiffEvidence evidence = new ExactDiffExecutor(new TrackingDiffEngine())
                .executeSuspectScopes(spec, rowReader(rows("A", 1)), rowReader(rows("A", 1)), scopes);

        assertTrue(evidence.completed);
        assertTrue(evidence.limitExceeded);
        assertTrue(evidence.diff.limitExceeded);
        assertEquals("global_timeout_millis", evidence.limitType);
        assertTrue(evidence.progressEvents.stream().anyMatch(event ->
                "exact_diff".equals(event.stage)
                        && "limit_exceeded".equals(event.status)
                        && "global_timeout_millis".equals(event.limitType)));
    }

    @Test
    void progressEventShouldCarryStageTaskRunSliceAndStatus() {
        ProgressEvent event = ProgressEvent.started("orders", "run-1", "global_signal", "dt=2026-03-10");

        assertEquals("orders", event.taskName);
        assertEquals("run-1", event.runId);
        assertEquals("global_signal", event.stage);
        assertEquals("dt=2026-03-10", event.sliceKey);
        assertEquals("started", event.status);
    }

    private TaskFileSpec validSpec() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "resource_governed";
        spec.source.type = "jdbc";
        spec.source.url = "jdbc:stub:source";
        spec.source.table = "source";
        spec.target.type = "jdbc";
        spec.target.url = "jdbc:stub:target";
        spec.target.table = "target";
        spec.output.dir = "./reports/resource_governed";
        return spec;
    }

    private RowStreamReader rowReader(List<Map<String, Object>> rows) {
        return (request, visitor) -> {
            for (Map<String, Object> row : rows) {
                if (request != null && request.sliceColumn != null
                        && !String.valueOf(row.get(request.sliceColumn)).equals(request.sliceValue)) {
                    continue;
                }
                if (request != null && request.sampleColumn != null
                        && request.sampleModulo != null
                        && request.sampleRemainder != null) {
                    int hash = Math.floorMod(String.valueOf(row.get(request.sampleColumn)).hashCode(), request.sampleModulo);
                    if (hash != request.sampleRemainder) {
                        continue;
                    }
                }
                visitor.accept(row);
            }
        };
    }

    private List<Map<String, Object>> rows(String valuePrefix, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("value", valuePrefix + i);
            row.put("dt", "2026-03-10");
            rows.add(row);
        }
        return rows;
    }

    private SliceDescriptor scope(String sliceKey) {
        SliceDescriptor scope = new SliceDescriptor();
        scope.sliceKey = sliceKey;
        scope.drilldownable = true;
        return scope;
    }

    private static final class TrackingDiffEngine extends DiffEngine {
        private final AtomicInteger current = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        private TrackingDiffEngine() {
            super(new NormalizationService());
        }

        @Override
        public DiffResult diff(RowStreamReader source, RowStreamReader target, TaskFileSpec spec, String sliceKey) throws Exception {
            int active = current.incrementAndGet();
            maxConcurrent.updateAndGet(previous -> Math.max(previous, active));
            try {
                Thread.sleep(20L);
                return super.diff(source, target, spec, sliceKey);
            } finally {
                current.decrementAndGet();
            }
        }
    }
}
