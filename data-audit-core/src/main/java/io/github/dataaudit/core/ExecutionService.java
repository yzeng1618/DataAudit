package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SamplingSummary;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import io.github.dataaudit.spi.report.ReportWriter;
import io.github.dataaudit.spi.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);
    private static final int DEFAULT_SAMPLE_MODULO = 10;

    private final ConnectorRegistry connectorRegistry;
    private final StateStore stateStore;
    private final ReportWriter reportWriter;
    private final SpecValidator validator;
    private final BoundaryResolver boundaryResolver;
    private final PlanningService planningService;
    private final SchemaEngine schemaEngine;
    private final SummaryEngine summaryEngine;
    private final SegmentEngine segmentEngine;
    private final DiffEngine diffEngine;
    private final DmlAuditor dmlAuditor;
    private final DdlAuditor ddlAuditor;

    public ExecutionService(ConnectorRegistry connectorRegistry,
                            StateStore stateStore,
                            ReportWriter reportWriter,
                            SpecValidator validator,
                            BoundaryResolver boundaryResolver,
                            PlanningService planningService,
                            SchemaEngine schemaEngine,
                            SummaryEngine summaryEngine,
                            SegmentEngine segmentEngine,
                            DiffEngine diffEngine,
                            DmlAuditor dmlAuditor,
                            DdlAuditor ddlAuditor) {
        this.connectorRegistry = connectorRegistry;
        this.stateStore = stateStore;
        this.reportWriter = reportWriter;
        this.validator = validator;
        this.boundaryResolver = boundaryResolver;
        this.planningService = planningService;
        this.schemaEngine = schemaEngine;
        this.summaryEngine = summaryEngine;
        this.segmentEngine = segmentEngine;
        this.diffEngine = diffEngine;
        this.dmlAuditor = dmlAuditor;
        this.ddlAuditor = ddlAuditor;
    }

    public ExecutionPlan plan(TaskFileSpec spec) throws Exception {
        validateOrThrow(spec);
        try (ConnectorBundle source = connectorRegistry.open(spec, spec.source);
             ConnectorBundle target = connectorRegistry.open(spec, spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, source, target);
            return planningService.plan(spec, source.getCapabilityDescriptor(), target.getCapabilityDescriptor(), boundary);
        }
    }

    public ReportModel check(TaskFileSpec spec) throws Exception {
        validateOrThrow(spec);
        stateStore.initialize();

        try (ConnectorBundle sourceBundle = connectorRegistry.open(spec, spec.source);
             ConnectorBundle targetBundle = connectorRegistry.open(spec, spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, sourceBundle, targetBundle);
            ExecutionPlan plan = planningService.plan(spec, sourceBundle.getCapabilityDescriptor(), targetBundle.getCapabilityDescriptor(), boundary);
            ReportModel report = baseReport(spec, plan);
            RunState runState = stateStore.startRun(spec.task.name, boundary.fingerprint, plan);
            report.runId = runState.runId;
            report.result.signalBackend = plan.signalBackend;

            if (plan.refuseReason != null) {
                report.result.status = "REFUSED";
                report.result.rootCause = plan.refuseReason;
                return persist(spec, runState.runId, report, "REFUSED", new ArrayList<>());
            }

            MetadataSnapshot sourceMetadata = readMetadata(sourceBundle, spec.boundary);
            MetadataSnapshot targetMetadata = readMetadata(targetBundle, spec.boundary);
            SchemaModel sourceSchema = sourceMetadata.schema;
            SchemaModel targetSchema = targetMetadata.schema;
            report.result.schemaIssues = schemaEngine.compare(spec, sourceSchema, targetSchema);
            report.result.ddlAudit = ddlAuditor.audit(spec, report.result.schemaIssues, sourceMetadata, targetMetadata);
            report.result.decisionTrace.addAll(report.result.ddlAudit.decisionTrace);

            RowStreamReader sourceRowReader = sourceBundle.getRowStreamReader();
            RowStreamReader targetRowReader = targetBundle.getRowStreamReader();
            SignalReader sourceSignalReader = sourceBundle.getSignalReader();
            SignalReader targetSignalReader = targetBundle.getSignalReader();
            if (sourceRowReader == null || targetRowReader == null || sourceSignalReader == null || targetSignalReader == null) {
                report.result.status = "PARTIAL";
                report.result.rootCause = "reader_unavailable";
                report.evidence.notes.add("At least one endpoint is missing row or signal reader support.");
                return persist(spec, runState.runId, report, "PARTIAL", report.result.suspectSlices);
            }

            if ("schema -> exact diff".equals(plan.selectedPath)) {
                exactCheck(spec, report, sourceRowReader, targetRowReader, sourceMetadata, targetMetadata, null);
                return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
            }

            SummaryMetrics sourceSummary = sourceSignalReader.readSummary(baseRequest(spec));
            SummaryMetrics targetSummary = targetSignalReader.readSummary(baseRequest(spec));
            report.result.sourceSummary = sourceSummary;
            report.result.targetSummary = targetSummary;

            if (summaryEngine.equivalent(sourceSummary, targetSummary) && report.result.schemaIssues.isEmpty()) {
                report.result.status = "CONSISTENT";
                report.result.rootCause = "consistent";
                report.result.consistencyLevel = "high_confidence";
                report.result.verdictBasis = plan.signalBackend;
                report.result.dmlAudit = dmlAuditor.audit(spec, sourceSummary, targetSummary, report.result.diff);
                report.result.decisionTrace.addAll(report.result.dmlAudit.decisionTrace);
                dmlAuditor.applyResumeHint(report);
                return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
            }

            if (isSmallForFullDrilldown(spec)) {
                exactCheck(spec, report, sourceRowReader, targetRowReader, sourceMetadata, targetMetadata, null);
                return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
            }

            List<SliceDescriptor> suspectSlices = new ArrayList<>();
            String naturalSliceColumn = segmentEngine.resolveSliceColumn(spec);
            if (naturalSliceColumn != null) {
                suspectSlices.addAll(segmentEngine.findSuspectSlices(sourceSignalReader, targetSignalReader, spec));
            } else if (hasKey(spec)) {
                suspectSlices.addAll(segmentEngine.findSuspectBuckets(sourceRowReader, targetRowReader, spec));
            }
            if (suspectSlices.isEmpty()) {
                suspectSlices.addAll(sourceMetadata.sliceHints);
                suspectSlices.addAll(targetMetadata.sliceHints);
            }
            report.result.suspectSlices = deduplicateSlices(suspectSlices);

            if (!report.result.suspectSlices.isEmpty()) {
                boolean sawSample = false;
                boolean exactResolved = true;
                boolean fullTableFallbackSampled = false;
                for (SliceDescriptor slice : report.result.suspectSlices) {
                    if (slice.drilldownable) {
                        DiffResult partial = diffEngine.diff(sourceRowReader, targetRowReader, spec, slice.sliceKey);
                        mergeDiff(report.result.diff, partial);
                        if (!partial.consistent) {
                            exactResolved = false;
                        }
                    } else {
                        String sampleSliceKey = isFilterableSlice(slice) ? slice.sliceKey : null;
                        if (sampleSliceKey == null && fullTableFallbackSampled) {
                            continue;
                        }
                        if (sampleSliceKey == null) {
                            fullTableFallbackSampled = true;
                        }
                        sawSample = true;
                        DiffResult sampled = sampleDiff(spec, sourceRowReader, targetRowReader, sampleSliceKey, report);
                        mergeDiff(report.result.diff, sampled);
                        if (!sampled.consistent) {
                            report.result.status = "DIFF_FOUND";
                            report.result.rootCause = resolveRootCause(spec, report.result.schemaIssues, sourceSummary, targetSummary, sampled);
                            report.result.consistencyLevel = "high_confidence";
                            report.result.verdictBasis = "deterministic_sampling";
                            report.result.dmlAudit = dmlAuditor.audit(spec, sourceSummary, targetSummary, sampled);
                            report.result.decisionTrace.addAll(report.result.dmlAudit.decisionTrace);
                            dmlAuditor.applyResumeHint(report);
                            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
                        }
                    }
                }

                if (report.result.diff.consistent) {
                    report.result.status = sawSample && !hasKey(spec) ? "INCONCLUSIVE" : "CONSISTENT";
                    report.result.rootCause = "consistent";
                    report.result.consistencyLevel = sawSample ? "high_confidence" : "high_confidence";
                    report.result.verdictBasis = sawSample ? "deterministic_sampling" : "slice_exact_diff";
                    if ("INCONCLUSIVE".equals(report.result.status)) {
                        report.result.inconclusiveReason = "sample_consistent_without_keyed_exact_proof";
                    }
                } else {
                    report.result.status = "DIFF_FOUND";
                    report.result.rootCause = resolveRootCause(spec, report.result.schemaIssues, sourceSummary, targetSummary, report.result.diff);
                    report.result.consistencyLevel = sawSample ? "high_confidence" : "exact";
                    report.result.verdictBasis = sawSample ? "deterministic_sampling" : "slice_exact_diff";
                }
            } else {
                DiffResult sampled = sampleDiff(spec, sourceRowReader, targetRowReader, null, report);
                report.result.diff = sampled;
                if (!sampled.consistent) {
                    report.result.status = "DIFF_FOUND";
                    report.result.rootCause = resolveRootCause(spec, report.result.schemaIssues, sourceSummary, targetSummary, sampled);
                    report.result.consistencyLevel = "high_confidence";
                    report.result.verdictBasis = "deterministic_sampling";
                } else if (hasKey(spec)) {
                    report.result.status = "CONSISTENT";
                    report.result.rootCause = "consistent";
                    report.result.consistencyLevel = "high_confidence";
                    report.result.verdictBasis = "deterministic_sampling";
                } else {
                    report.result.status = "INCONCLUSIVE";
                    report.result.rootCause = "sampling_inconclusive";
                    report.result.consistencyLevel = "high_confidence";
                    report.result.verdictBasis = "deterministic_sampling";
                    report.result.inconclusiveReason = "keyless_large_object_requires_exact_or_natural_slice";
                }
            }

            report.result.dmlAudit = dmlAuditor.audit(spec, sourceSummary, targetSummary, report.result.diff);
            report.result.decisionTrace.addAll(report.result.dmlAudit.decisionTrace);
            if (!"DIFF_FOUND".equals(report.result.status) && !"INCONCLUSIVE".equals(report.result.status)) {
                report.result.rootCause = resolveRootCause(spec, report.result.schemaIssues, sourceSummary, targetSummary, report.result.diff);
            }
            dmlAuditor.applyResumeHint(report);
            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
        }
    }

    public ReportModel diff(TaskFileSpec spec, String sliceKey) throws Exception {
        validateOrThrow(spec);
        stateStore.initialize();

        try (ConnectorBundle sourceBundle = connectorRegistry.open(spec, spec.source);
             ConnectorBundle targetBundle = connectorRegistry.open(spec, spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, sourceBundle, targetBundle);
            ExecutionPlan plan = planningService.plan(spec, sourceBundle.getCapabilityDescriptor(), targetBundle.getCapabilityDescriptor(), boundary);
            ReportModel report = baseReport(spec, plan);
            RunState runState = stateStore.startRun(spec.task.name + "-diff", boundary.fingerprint, plan);
            report.runId = runState.runId;
            if (sourceBundle.getRowStreamReader() == null || targetBundle.getRowStreamReader() == null) {
                report.result.status = "PARTIAL";
                report.result.rootCause = "reader_unavailable";
                return persist(spec, runState.runId, report, "PARTIAL", new ArrayList<>());
            }

            report.result.diff = diffEngine.diff(sourceBundle.getRowStreamReader(), targetBundle.getRowStreamReader(), spec, sliceKey);
            report.result.status = report.result.diff.consistent ? "CONSISTENT" : "DIFF_FOUND";
            report.result.rootCause = report.result.diff.rootCause;
            report.result.consistencyLevel = "exact";
            report.result.verdictBasis = "exact_diff";
            report.result.dmlAudit = dmlAuditor.audit(spec, null, null, report.result.diff);
            if (sliceKey != null) {
                SliceDescriptor descriptor = new SliceDescriptor();
                descriptor.sliceKey = sliceKey;
                descriptor.sliceType = "manual";
                report.result.suspectSlices.add(descriptor);
            }
            dmlAuditor.applyResumeHint(report);
            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
        }
    }

    private void exactCheck(TaskFileSpec spec,
                            ReportModel report,
                            RowStreamReader sourceReader,
                            RowStreamReader targetReader,
                            MetadataSnapshot sourceMetadata,
                            MetadataSnapshot targetMetadata,
                            String sliceKey) throws Exception {
        List<Map<String, Object>> sourceRows = collectRows(sourceReader, sliceKey == null ? baseRequest(spec) : requestForSlice(spec, sliceKey));
        List<Map<String, Object>> targetRows = collectRows(targetReader, sliceKey == null ? baseRequest(spec) : requestForSlice(spec, sliceKey));
        SummaryMetrics sourceSummary = summaryEngine.summarizeRows(sourceRows, spec);
        SummaryMetrics targetSummary = summaryEngine.summarizeRows(targetRows, spec);
        report.result.sourceSummary = sourceSummary;
        report.result.targetSummary = targetSummary;
        report.result.diff = diffEngine.diffRows(sourceRows, targetRows, spec, sliceKey);
        report.result.dmlAudit = dmlAuditor.audit(spec, sourceSummary, targetSummary, report.result.diff);
        report.result.decisionTrace.addAll(report.result.dmlAudit.decisionTrace);
        report.result.status = report.result.diff.consistent && report.result.schemaIssues.isEmpty() ? "CONSISTENT" : "DIFF_FOUND";
        report.result.rootCause = resolveRootCause(spec, report.result.schemaIssues, sourceSummary, targetSummary, report.result.diff);
        report.result.consistencyLevel = "exact";
        report.result.verdictBasis = "exact_diff";
        if (sliceKey != null) {
            SliceDescriptor descriptor = new SliceDescriptor();
            descriptor.sliceKey = sliceKey;
            descriptor.sliceType = "exact_slice";
            descriptor.rowEstimate = (long) Math.max(sourceRows.size(), targetRows.size());
            report.result.suspectSlices.add(descriptor);
        }
        dmlAuditor.applyResumeHint(report);
    }

    private DiffResult sampleDiff(TaskFileSpec spec,
                                  RowStreamReader sourceReader,
                                  RowStreamReader targetReader,
                                  String sliceKey,
                                  ReportModel report) throws Exception {
        String sampleColumn = chooseSampleColumn(spec);
        ReadRequest sourceRequest = requestForSample(spec, sampleColumn, sliceKey);
        ReadRequest targetRequest = requestForSample(spec, sampleColumn, sliceKey);
        List<Map<String, Object>> sourceRows = collectRows(sourceReader, sourceRequest);
        List<Map<String, Object>> targetRows = collectRows(targetReader, targetRequest);
        int[] bucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        SamplingSummary samplingSummary = new SamplingSummary();
        samplingSummary.used = true;
        samplingSummary.mode = bucket == null ? "deterministic_hash_mod" : "deterministic_bucket";
        samplingSummary.sampleColumn = sampleColumn;
        samplingSummary.sampleModulo = bucket == null ? DEFAULT_SAMPLE_MODULO : bucket[1];
        samplingSummary.sampleRemainder = bucket == null ? 0 : bucket[0];
        samplingSummary.sourceRows = (long) sourceRows.size();
        samplingSummary.targetRows = (long) targetRows.size();
        report.result.samplingSummary = samplingSummary;
        DiffResult sampled = diffEngine.diffRows(sourceRows, targetRows, spec, sliceKey);
        sampled.sampled = true;
        return sampled;
    }

    private MetadataSnapshot readMetadata(ConnectorBundle bundle, TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        MetadataReader reader = bundle.getMetadataReader();
        if (reader != null) {
            return reader.readMetadata(boundarySpec);
        }
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.boundary = new BoundaryRef();
        snapshot.boundary.type = boundarySpec.type;
        snapshot.boundary.reference = boundarySpec.reference;
        if (bundle.getSchemaReader() != null) {
            snapshot.schema = bundle.getSchemaReader().readSchema();
        }
        return snapshot;
    }

    private ReportModel persist(TaskFileSpec spec,
                                String runId,
                                ReportModel report,
                                String status,
                                List<SliceDescriptor> slices) throws Exception {
        stateStore.saveSlices(runId, slices, status);
        Path outputDir = Paths.get(spec.output.dir);
        ReportWriter.ReportArtifacts artifacts = reportWriter.write(report, outputDir);
        stateStore.completeRun(runId, status, artifacts.getJsonPath(), artifacts.getHtmlPath());
        stateStore.attachReport(runId, report);
        return report;
    }

    private ReportModel baseReport(TaskFileSpec spec, ExecutionPlan plan) {
        ReportModel report = new ReportModel();
        report.plan.taskName = spec.task.name;
        report.plan.objectClass = plan.objectClass;
        report.plan.selectedPath = plan.selectedPath;
        report.plan.signalBackend = plan.signalBackend;
        report.plan.signalStrategy = plan.signalStrategy;
        report.plan.localizationStrategy = plan.localizationStrategy;
        report.plan.executedLevels = plan.executedLevels;
        report.plan.decisionTrace = plan.decisionTrace;
        report.plan.boundary = plan.boundary;
        report.plan.reason = plan.reason;
        return report;
    }

    private void validateOrThrow(TaskFileSpec spec) {
        List<String> issues = validator.validate(spec);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", issues));
        }
    }

    private ReadRequest baseRequest(TaskFileSpec spec) {
        return ReadRequestFactory.baseRequest(spec);
    }

    private ReadRequest requestForSlice(TaskFileSpec spec, String sliceKey) {
        int[] bucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        if (bucket != null && hasKey(spec)) {
            return ReadRequestFactory.bucketRequest(spec, spec.object.key.get(0), bucket[1], bucket[0]);
        }
        if (sliceKey != null && sliceKey.contains("=")) {
            String[] parts = sliceKey.split("=", 2);
            return ReadRequestFactory.sliceRequest(spec, parts[0], parts[1]);
        }
        return baseRequest(spec);
    }

    private ReadRequest requestForSample(TaskFileSpec spec, String sampleColumn, String sliceKey) {
        int[] bucket = ReadRequestFactory.parseVirtualBucket(sliceKey);
        if (bucket != null && hasKey(spec)) {
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

    private List<Map<String, Object>> collectRows(RowStreamReader reader, ReadRequest request) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        reader.scanRows(request, rows::add);
        return rows;
    }

    private List<SliceDescriptor> deduplicateSlices(List<SliceDescriptor> slices) {
        List<SliceDescriptor> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (SliceDescriptor slice : slices) {
            if (slice == null || slice.sliceKey == null || seen.contains(slice.sliceKey)) {
                continue;
            }
            seen.add(slice.sliceKey);
            result.add(slice);
        }
        return result;
    }

    private void mergeDiff(DiffResult merged, DiffResult partial) {
        merged.consistent = merged.consistent && partial.consistent;
        merged.sampled = merged.sampled || partial.sampled;
        merged.samples.addAll(partial.samples);
        if (!partial.consistent) {
            merged.rootCause = partial.rootCause;
        }
    }

    private boolean hasKey(TaskFileSpec spec) {
        return spec.object != null && spec.object.key != null && !spec.object.key.isEmpty();
    }

    private boolean isSmallForFullDrilldown(TaskFileSpec spec) {
        long estimatedRows = estimatedRows(spec);
        return estimatedRows >= 0 && estimatedRows <= PlanningService.MAX_EXACT_ROWS;
    }

    private long estimatedRows(TaskFileSpec spec) {
        return spec.object == null || spec.object.estimatedRows == null ? -1L : spec.object.estimatedRows;
    }

    private boolean isFilterableSlice(SliceDescriptor slice) {
        return slice != null && !"metadata_hint".equalsIgnoreCase(slice.sliceType);
    }

    private String chooseSampleColumn(TaskFileSpec spec) {
        if (hasKey(spec)) {
            return spec.object.key.get(0);
        }
        if (spec.object != null && spec.object.columns != null && !spec.object.columns.isEmpty()) {
            return spec.object.columns.get(0);
        }
        return "row_digest";
    }

    private String resolveRootCause(TaskFileSpec spec,
                                    List<String> schemaIssues,
                                    SummaryMetrics sourceSummary,
                                    SummaryMetrics targetSummary,
                                    DiffResult diff) {
        String ddlRootCause = ddlAuditor.classify(schemaIssues);
        if (!"consistent".equalsIgnoreCase(ddlRootCause)) {
            return ddlRootCause;
        }
        return dmlAuditor.classify(spec, sourceSummary, targetSummary, diff);
    }
}
