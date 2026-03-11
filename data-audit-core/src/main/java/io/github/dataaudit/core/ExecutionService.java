package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.DataReader;
import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SegmentDescriptor;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import io.github.dataaudit.spi.report.ReportWriter;
import io.github.dataaudit.spi.state.StateStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ExecutionService {
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
    private final AuditService auditService;

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
                            AuditService auditService) {
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
        this.auditService = auditService;
    }

    public ExecutionPlan plan(TaskFileSpec spec) throws Exception {
        validateOrThrow(spec);
        try (ConnectorBundle source = connectorRegistry.open(spec.source);
             ConnectorBundle target = connectorRegistry.open(spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, source, target);
            return planningService.plan(spec, source.getCapabilityDescriptor(), target.getCapabilityDescriptor(), boundary);
        }
    }

    public ReportModel check(TaskFileSpec spec) throws Exception {
        validateOrThrow(spec);
        stateStore.initialize();

        try (ConnectorBundle sourceBundle = connectorRegistry.open(spec.source);
             ConnectorBundle targetBundle = connectorRegistry.open(spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, sourceBundle, targetBundle);
            ExecutionPlan plan = planningService.plan(spec, sourceBundle.getCapabilityDescriptor(), targetBundle.getCapabilityDescriptor(), boundary);

            ReportModel report = baseReport(spec, plan);
            RunState runState = stateStore.startRun(spec.task.name, boundary.fingerprint, plan);
            report.runId = runState.runId;

            if (plan.refuseReason != null) {
                report.result.status = "REFUSED";
                report.result.rootCause = plan.refuseReason;
                return persist(spec, runState.runId, report, "REFUSED", new ArrayList<SegmentDescriptor>());
            }

            MetadataSnapshot sourceMetadata = readMetadata(sourceBundle.getMetadataReader(), spec.boundary);
            MetadataSnapshot targetMetadata = readMetadata(targetBundle.getMetadataReader(), spec.boundary);
            SchemaModel sourceSchema = sourceMetadata.schema;
            SchemaModel targetSchema = targetMetadata.schema;
            report.result.schemaIssues = schemaEngine.compare(spec, sourceSchema, targetSchema);

            DataReader sourceReader = sourceBundle.getDataReader();
            DataReader targetReader = targetBundle.getDataReader();

            if (sourceReader == null || targetReader == null) {
                report.result.status = "PARTIAL";
                report.result.rootCause = "data_reader_unavailable";
                report.evidence.notes.add("At least one endpoint does not expose a data reader. Metadata-only report generated.");
                mergeMetadataHints(report, sourceMetadata, targetMetadata);
                auditService.applyResumeHint(report);
                return persist(spec, runState.runId, report, "PARTIAL", report.result.suspectSegments);
            }

            if ("schema -> exact diff".equals(plan.selectedPath)) {
                SummaryMetrics sourceSummary = summaryEngine.summarize(sourceReader, spec, new ReadRequest());
                SummaryMetrics targetSummary = summaryEngine.summarize(targetReader, spec, new ReadRequest());
                report.result.sourceSummary = sourceSummary;
                report.result.targetSummary = targetSummary;
                DiffResult diff = diffEngine.diff(sourceReader, targetReader, spec, null);
                report.result.diff = diff;
                report.result.status = diff.consistent && report.result.schemaIssues.isEmpty() ? "CONSISTENT" : "DIFF_FOUND";
                report.result.rootCause = auditService.classify(report.result.schemaIssues, sourceSummary, targetSummary, diff);
                auditService.applyResumeHint(report);
                return persist(spec, runState.runId, report, report.result.status, report.result.suspectSegments);
            }

            SummaryMetrics sourceSummary = summaryEngine.summarize(sourceReader, spec, new ReadRequest());
            SummaryMetrics targetSummary = summaryEngine.summarize(targetReader, spec, new ReadRequest());
            report.result.sourceSummary = sourceSummary;
            report.result.targetSummary = targetSummary;

            if (summaryEngine.equivalent(sourceSummary, targetSummary) && report.result.schemaIssues.isEmpty()) {
                report.result.status = "CONSISTENT";
                report.result.rootCause = "consistent";
                mergeMetadataHints(report, sourceMetadata, targetMetadata);
                auditService.applyResumeHint(report);
                return persist(spec, runState.runId, report, "CONSISTENT", report.result.suspectSegments);
            }

            List<SegmentDescriptor> suspects = segmentEngine.findSuspectSegments(sourceReader, targetReader, spec);
            if (suspects.isEmpty()) {
                DiffResult diff = diffEngine.diff(sourceReader, targetReader, spec, null);
                report.result.diff = diff;
                report.result.status = diff.consistent ? "CONSISTENT" : "DIFF_FOUND";
            } else {
                report.result.suspectSegments = suspects;
                DiffResult merged = new DiffResult();
                merged.consistent = true;
                for (SegmentDescriptor suspect : suspects) {
                    DiffResult partial = diffEngine.diff(sourceReader, targetReader, spec, suspect.segmentKey);
                    merged.consistent = merged.consistent && partial.consistent;
                    merged.samples.addAll(partial.samples);
                    if (!partial.consistent) {
                        merged.rootCause = partial.rootCause;
                    }
                }
                report.result.diff = merged;
                report.result.status = merged.consistent ? "CONSISTENT" : "DIFF_FOUND";
            }

            report.result.rootCause = auditService.classify(report.result.schemaIssues, sourceSummary, targetSummary, report.result.diff);
            mergeMetadataHints(report, sourceMetadata, targetMetadata);
            auditService.applyResumeHint(report);
            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSegments);
        }
    }

    public ReportModel diff(TaskFileSpec spec, String segmentKey) throws Exception {
        validateOrThrow(spec);
        stateStore.initialize();

        try (ConnectorBundle sourceBundle = connectorRegistry.open(spec.source);
             ConnectorBundle targetBundle = connectorRegistry.open(spec.target)) {
            BoundaryRef boundary = boundaryResolver.resolve(spec, sourceBundle, targetBundle);
            ExecutionPlan plan = planningService.plan(spec, sourceBundle.getCapabilityDescriptor(), targetBundle.getCapabilityDescriptor(), boundary);

            ReportModel report = baseReport(spec, plan);
            RunState runState = stateStore.startRun(spec.task.name + "-diff", boundary.fingerprint, plan);
            report.runId = runState.runId;

            if (sourceBundle.getDataReader() == null || targetBundle.getDataReader() == null) {
                report.result.status = "PARTIAL";
                report.result.rootCause = "data_reader_unavailable";
                return persist(spec, runState.runId, report, "PARTIAL", new ArrayList<SegmentDescriptor>());
            }

            report.result.diff = diffEngine.diff(sourceBundle.getDataReader(), targetBundle.getDataReader(), spec, segmentKey);
            report.result.status = report.result.diff.consistent ? "CONSISTENT" : "DIFF_FOUND";
            report.result.rootCause = report.result.diff.rootCause;
            if (segmentKey != null) {
                SegmentDescriptor descriptor = new SegmentDescriptor();
                descriptor.segmentKey = segmentKey;
                report.result.suspectSegments.add(descriptor);
            }
            auditService.applyResumeHint(report);
            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSegments);
        }
    }

    private MetadataSnapshot readMetadata(MetadataReader reader, TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        if (reader == null) {
            return new MetadataSnapshot();
        }
        return reader.readMetadata(boundarySpec);
    }

    private ReportModel persist(TaskFileSpec spec,
                                String runId,
                                ReportModel report,
                                String status,
                                List<SegmentDescriptor> segments) throws Exception {
        stateStore.saveSegments(runId, segments, status);
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
        report.plan.executedLevels = plan.executedLevels;
        report.plan.boundary = plan.boundary;
        report.plan.reason = plan.reason;
        return report;
    }

    private void mergeMetadataHints(ReportModel report, MetadataSnapshot sourceMetadata, MetadataSnapshot targetMetadata) {
        if (sourceMetadata != null && sourceMetadata.segmentHints != null) {
            report.result.suspectSegments.addAll(sourceMetadata.segmentHints);
        }
        if (targetMetadata != null && targetMetadata.segmentHints != null) {
            report.result.suspectSegments.addAll(targetMetadata.segmentHints);
        }
    }

    private void validateOrThrow(TaskFileSpec spec) {
        List<String> issues = validator.validate(spec);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", issues));
        }
    }
}
