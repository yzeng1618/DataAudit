package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import io.github.dataaudit.spi.report.ReportWriter;
import io.github.dataaudit.spi.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionServiceScalePipelineTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldStopAtUnstableBoundaryBeforeDataRootCause() throws Exception {
        TaskFileSpec spec = baseSpec("unstable_boundary");
        spec.boundary.type = "snapshot";
        spec.boundary.reference = "latest";

        ExecutionService service = newExecutionService(
                bundle(summaryReader(1L, "1:1"), emptyRowReader()),
                bundle(summaryReader(1L, "1:1"), emptyRowReader())
        );

        ReportModel report = service.check(spec);

        assertEquals("UNSTABLE_BOUNDARY", report.result.status);
        assertNull(report.result.rootCause);
    }

    @Test
    void shouldShortCircuitSmallConsistentTableAtGlobalChecksum() throws Exception {
        TaskFileSpec spec = baseSpec("small_consistent");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 100L;

        ExecutionService service = newExecutionService(
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A"))),
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A")))
        );

        ReportModel report = service.check(spec);

        assertEquals("CONSISTENT", report.result.status);
        assertEquals(ProofMode.GLOBAL_CHECKSUM, report.result.proofMode);
        assertEquals(ConfidenceLevel.HIGH, report.result.confidence);
        assertFalse(report.evidence.exactDiff.completed);
    }

    @Test
    void shouldExposeProofFieldsForLargeNoKeyFallbackDiff() throws Exception {
        TaskFileSpec spec = baseSpec("large_no_key_diff");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 1_000_000L;

        ExecutionService service = newExecutionService(
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A"))),
                bundle(summaryReader(1L, "2:2"), rowReader(row("id", 1, "value", "B")))
        );

        ReportModel report = service.check(spec);

        assertEquals("DIFF_FOUND", report.result.status);
        assertEquals(ProofMode.XOR_CHECKSUM_PLUS_SAMPLE, report.result.proofMode);
        assertEquals(ConfidenceLevel.MEDIUM, report.result.confidence);
        assertTrue(report.result.noKeyMode);
        assertEquals("no_key_xor_fallback", report.result.fallbackReason);
        assertEquals("masked", report.evidenceValueMode);
        assertFalse(report.result.diff.samples.isEmpty());
        assertTrue(report.result.diff.samples.stream().allMatch(sample ->
                sample.key == null || "***".equals(sample.key)));
        assertTrue(report.result.diff.samples.stream().allMatch(sample ->
                sample.sourceValue == null || "***".equals(sample.sourceValue)));
        assertTrue(report.result.diff.samples.stream().allMatch(sample ->
                sample.targetValue == null || "***".equals(sample.targetValue)));
    }

    @Test
    void shouldAttachProgressEventsWithRunIdForCheckStages() throws Exception {
        TaskFileSpec spec = baseSpec("large_progress_events");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 1_000_000L;

        ExecutionService service = newExecutionService(
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A"))),
                bundle(summaryReader(1L, "2:2"), rowReader(row("id", 1, "value", "B")))
        );

        ReportModel report = service.check(spec);

        assertTrue(report.evidence.progressEvents.stream().anyMatch(event ->
                "global_signal".equals(event.stage) && "started".equals(event.status)));
        assertTrue(report.evidence.progressEvents.stream().anyMatch(event ->
                "localization".equals(event.stage) && "completed".equals(event.status)));
        assertTrue(report.evidence.progressEvents.stream().anyMatch(event ->
                "exact_diff".equals(event.stage) && "completed".equals(event.status)));
        assertTrue(report.evidence.progressEvents.stream().allMatch(event -> report.runId.equals(event.runId)));
    }

    @Test
    void shouldRequireLowConfidenceWhenSamplingReportsConsistent() throws Exception {
        TaskFileSpec spec = baseSpec("xlarge_sampling_consistent");
        spec.object.columns.add("id");
        spec.object.columns.add("value");
        spec.object.estimatedRows = 200_000_000L;

        ExecutionService service = newExecutionService(
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A"))),
                bundle(summaryReader(1L, "1:1"), rowReader(row("id", 1, "value", "A")))
        );

        ReportModel report = service.check(spec);

        assertEquals("CONSISTENT", report.result.status);
        assertEquals(ProofMode.SAMPLING, report.result.proofMode);
        assertEquals(ConfidenceLevel.LOW, report.result.confidence);
        assertTrue(report.result.noKeyMode);
        assertEquals("xlarge_sampling_fallback", report.result.fallbackReason);
    }

    private ExecutionService newExecutionService(ConnectorBundle sourceBundle,
                                                 ConnectorBundle targetBundle) {
        SummaryEngine summaryEngine = new SummaryEngine(new NormalizationService(), new HashProvider());
        return new ExecutionService(
                new ConnectorRegistry(List.of(new StubConnectorFactory(sourceBundle, targetBundle))),
                new InMemoryStateStore(),
                new InMemoryReportWriter(tempDir),
                new SpecValidator(),
                new BoundaryResolver(),
                new PlanningService(),
                new SchemaEngine(),
                summaryEngine,
                new SegmentEngine(summaryEngine),
                new DiffEngine(new NormalizationService()),
                new DmlAuditor(),
                new DdlAuditor()
        );
    }

    private TaskFileSpec baseSpec(String taskName) {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = taskName;
        spec.output.dir = tempDir.resolve(taskName).toString();
        spec.source.type = "jdbc";
        spec.source.url = "jdbc:stub:source";
        spec.source.table = "source";
        spec.target.type = "jdbc";
        spec.target.url = "jdbc:stub:target";
        spec.target.table = "target";
        return spec;
    }

    private ConnectorBundle bundle(SignalReader signalReader, RowStreamReader rowStreamReader) {
        return new ConnectorBundle(null, null, signalReader, rowStreamReader, null, null);
    }

    private SignalReader summaryReader(long rowCount, String checksum) {
        return new SignalReader() {
            @Override
            public SummaryMetrics readSummary(ReadRequest request) {
                SummaryMetrics metrics = new SummaryMetrics();
                metrics.rowCount = rowCount;
                metrics.checksum = checksum;
                return metrics;
            }

            @Override
            public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) {
                return new ArrayList<>();
            }
        };
    }

    private RowStreamReader emptyRowReader() {
        return (request, visitor) -> {
        };
    }

    private RowStreamReader rowReader(Map<String, Object> row) {
        return (request, visitor) -> visitor.accept(row);
    }

    private Map<String, Object> row(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key1, value1);
        row.put(key2, value2);
        return row;
    }

    private static final class StubConnectorFactory implements ConnectorFactory {
        private final ConnectorBundle sourceBundle;
        private final ConnectorBundle targetBundle;

        private StubConnectorFactory(ConnectorBundle sourceBundle, ConnectorBundle targetBundle) {
            this.sourceBundle = sourceBundle;
            this.targetBundle = targetBundle;
        }

        @Override
        public String type() {
            return "jdbc";
        }

        @Override
        public boolean supports(TaskFileSpec.EndpointSpec endpointSpec) {
            return endpointSpec != null && "jdbc".equalsIgnoreCase(endpointSpec.type);
        }

        @Override
        public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
            return "source".equals(endpointSpec.table) ? sourceBundle : targetBundle;
        }
    }

    private static final class InMemoryStateStore implements StateStore {
        @Override
        public void initialize() {
        }

        @Override
        public RunState startRun(String taskName, String boundaryFingerprint, ExecutionPlan plan) {
            RunState state = new RunState();
            state.runId = UUID.randomUUID().toString();
            state.taskName = taskName;
            state.boundaryFingerprint = boundaryFingerprint;
            state.startedAt = OffsetDateTime.now();
            return state;
        }

        @Override
        public void saveSlices(String runId, List<SliceDescriptor> slices, String status) {
        }

        @Override
        public void completeRun(String runId, String status, Path reportJsonPath, Path reportHtmlPath) {
        }

        @Override
        public Optional<RunState> findLatestRun(String taskName) {
            return Optional.empty();
        }

        @Override
        public Optional<RunState> findRun(String runId) {
            return Optional.empty();
        }

        @Override
        public void attachReport(String runId, ReportModel report) {
        }
    }

    private static final class InMemoryReportWriter implements ReportWriter {
        private final Path outputRoot;

        private InMemoryReportWriter(Path outputRoot) {
            this.outputRoot = outputRoot;
        }

        @Override
        public ReportArtifacts write(ReportModel report, Path outputDir) {
            Path effectiveOutput = outputDir == null ? outputRoot : outputDir;
            return new ReportArtifacts(effectiveOutput.resolve("report.json"), effectiveOutput.resolve("report.html"));
        }
    }
}
