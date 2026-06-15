package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.BoundaryStatus;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.ExactDiffEvidence;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.GlobalSignalEvidence;
import io.github.dataaudit.spi.model.LocalizationEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ProgressEvent;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SliceDescriptor;
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
    private final SummaryEngine summaryEngine;
    private final DiffEngine diffEngine;

    private final BoundaryGate boundaryGate = new BoundaryGate();
    private final ScaleClassifier scaleClassifier = new ScaleClassifier();
    private final SignalEngine signalEngine = new SignalEngine();
    private final LocalizationEngine localizationEngine = new LocalizationEngine();
    private final RootCauseEngine rootCauseEngine = new RootCauseEngine();
    private final ExactDiffExecutor exactDiffExecutor;

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
        this.summaryEngine = summaryEngine;
        this.diffEngine = diffEngine;
        this.exactDiffExecutor = new ExactDiffExecutor(diffEngine);
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
            BoundaryContext boundaryContext = boundaryGate.evaluate(boundary);
            if (boundaryContext.status == BoundaryStatus.UNSTABLE) {
                report.result.status = "UNSTABLE_BOUNDARY";
                report.result.rootCause = null;
                report.evidence.notes.add(boundary == null ? "boundary unavailable" : boundary.detail);
                return persist(spec, runState.runId, report, report.result.status, new ArrayList<>());
            }

            RowStreamReader sourceRowReader = sourceBundle.getRowStreamReader();
            RowStreamReader targetRowReader = targetBundle.getRowStreamReader();
            SignalReader sourceSignalReader = sourceBundle.getSignalReader();
            SignalReader targetSignalReader = targetBundle.getSignalReader();
            if (sourceRowReader == null || targetRowReader == null || sourceSignalReader == null || targetSignalReader == null) {
                report.result.status = "EXECUTION_FAILED";
                report.result.rootCause = null;
                report.evidence.notes.add("At least one endpoint is missing row or signal reader support.");
                return persist(spec, runState.runId, report, report.result.status, new ArrayList<>());
            }

            ScaleClass scaleClass = plan.scaleClass == null ? scaleClassifier.classify(spec) : plan.scaleClass;
            report.plan.scaleClass = scaleClass;

            addProgressEvent(report, spec, runState.runId, "global_signal", null, "started");
            GlobalSignalEvidence globalSignal = signalEngine.evaluate(spec, scaleClass, sourceSignalReader, targetSignalReader);
            addProgressEvent(report, spec, runState.runId, "global_signal", null, "completed");
            report.evidence.globalSignal = globalSignal;
            report.result.sourceSummary = globalSignal.sourceSummary;
            report.result.targetSummary = globalSignal.targetSummary;

            boolean globalEquivalent = summaryEquivalent(globalSignal);
            addProgressEvent(report, spec, runState.runId, "localization", null, "started");
            LocalizationEvidence localization = prepareLocalization(
                    spec,
                    scaleClass,
                    globalEquivalent,
                    sourceBundle,
                    targetBundle,
                    sourceSignalReader,
                    targetSignalReader,
                    sourceRowReader,
                    targetRowReader
            );
            addProgressEvent(report, spec, runState.runId, "localization", null, "completed");
            report.evidence.localization = localization;
            applyLocalization(report, localization);

            ExactDiffEvidence exactDiff = exactDiffExecutor.execute(spec, scaleClass, sourceRowReader, targetRowReader, localization);
            attachExactDiffProgress(report, spec, runState.runId, exactDiff);
            report.evidence.exactDiff = exactDiff;
            report.result.diff = exactDiff.diff;
            report.result.samplingSummary = exactDiff.samplingSummary;
            report.result.suspectSlices = deduplicateSlices(localization.suspiciousScopes);

            finalizeResult(report, scaleClass, globalEquivalent, localization, exactDiff);
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
                report.result.status = "EXECUTION_FAILED";
                report.result.rootCause = null;
                return persist(spec, runState.runId, report, report.result.status, new ArrayList<>());
            }

            addProgressEvent(report, spec, runState.runId, "exact_diff", sliceKey, "started");
            report.result.diff = diffEngine.diff(sourceBundle.getRowStreamReader(), targetBundle.getRowStreamReader(), spec, sliceKey);
            if (report.result.diff.limitExceeded) {
                addLimitEvent(report, spec, runState.runId, "exact_diff", sliceKey, report.result.diff);
            }
            addProgressEvent(report, spec, runState.runId, "exact_diff", sliceKey, "completed");
            report.evidence.exactDiff.completed = true;
            report.evidence.exactDiff.diff = report.result.diff;
            report.evidence.exactDiff.limitExceeded = report.result.diff.limitExceeded;
            report.evidence.exactDiff.limitType = report.result.diff.limitType;
            report.evidence.exactDiff.progressEvents.addAll(report.evidence.progressEvents);
            report.result.status = report.result.diff.consistent ? "CONSISTENT" : "DIFF_FOUND";
            report.result.rootCause = report.result.diff.consistent ? null : resolveRootCause(null, report.result.diff);
            if (report.result.diff.limitExceeded) {
                report.result.fallbackReason = report.result.diff.fallbackReason;
                report.result.proofMode = ProofMode.SAMPLING;
                report.result.confidence = ConfidenceLevel.LOW;
            } else {
                report.result.proofMode = ProofMode.EXACT_DIFF;
                report.result.confidence = ConfidenceLevel.EXACT;
            }
            if (sliceKey != null) {
                SliceDescriptor descriptor = new SliceDescriptor();
                descriptor.sliceKey = sliceKey;
                descriptor.sliceType = "manual";
                report.result.suspectSlices.add(descriptor);
            }
            return persist(spec, runState.runId, report, report.result.status, report.result.suspectSlices);
        }
    }

    private LocalizationEvidence prepareLocalization(TaskFileSpec spec,
                                                     ScaleClass scaleClass,
                                                     boolean globalEquivalent,
                                                     ConnectorBundle sourceBundle,
                                                     ConnectorBundle targetBundle,
                                                     SignalReader sourceSignalReader,
                                                     SignalReader targetSignalReader,
                                                     RowStreamReader sourceRowReader,
                                                     RowStreamReader targetRowReader) throws Exception {
        if (scaleClass == ScaleClass.SMALL) {
            LocalizationEvidence localization = new LocalizationEvidence();
            localization.strategy = "none";
            localization.proofMode = globalEquivalent ? ProofMode.GLOBAL_CHECKSUM : ProofMode.EXACT_DIFF;
            localization.confidence = globalEquivalent ? ConfidenceLevel.HIGH : ConfidenceLevel.EXACT;
            return localization;
        }

        return localizationEngine.localize(
                spec,
                scaleClass,
                sourceSignalReader,
                targetSignalReader,
                sourceBundle.getRoutingSignalReader(),
                targetBundle.getRoutingSignalReader(),
                sourceRowReader,
                targetRowReader
        );
    }

    private void applyLocalization(ReportModel report, LocalizationEvidence localization) {
        report.result.proofMode = localization.proofMode;
        report.result.confidence = localization.confidence;
        report.result.noKeyMode = localization.noKeyMode;
        report.result.fallbackReason = localization.fallbackReason;
        report.plan.localizationStrategy = localization.strategy == null ? report.plan.localizationStrategy : localization.strategy;
    }

    private void finalizeResult(ReportModel report,
                                ScaleClass scaleClass,
                                boolean globalEquivalent,
                                LocalizationEvidence localization,
                                ExactDiffEvidence exactDiff) {
        if (scaleClass == ScaleClass.SMALL && globalEquivalent && !exactDiff.completed) {
            report.result.status = "CONSISTENT";
            report.result.rootCause = null;
            report.result.proofMode = ProofMode.GLOBAL_CHECKSUM;
            report.result.confidence = ConfidenceLevel.HIGH;
            return;
        }

        if (exactDiff.completed) {
            if (exactDiff.diff != null && exactDiff.diff.limitExceeded) {
                report.result.status = "DIFF_FOUND";
                report.result.rootCause = exactDiff.diff.rootCause;
                report.result.fallbackReason = exactDiff.diff.fallbackReason == null
                        ? report.result.fallbackReason
                        : exactDiff.diff.fallbackReason;
                if (report.result.proofMode == null || report.result.proofMode == ProofMode.EXACT_DIFF) {
                    report.result.proofMode = ProofMode.SAMPLING;
                }
                report.result.confidence = ConfidenceLevel.LOW;
                return;
            }
            if (exactDiff.diff.consistent
                    && localization != null
                    && localization.proofMode == ProofMode.XOR_CHECKSUM_PLUS_SAMPLE
                    && localization.suspiciousScopes != null
                    && !localization.suspiciousScopes.isEmpty()) {
                report.result.status = "DIFF_FOUND";
                report.result.rootCause = resolveSignalOnlyRootCause(report.evidence.globalSignal, localization);
                return;
            }
            if (exactDiff.diff.consistent) {
                report.result.status = "CONSISTENT";
                report.result.rootCause = null;
            } else {
                report.result.status = "DIFF_FOUND";
                report.result.rootCause = resolveRootCause(report.evidence.globalSignal, exactDiff.diff);
            }
            if (!exactDiff.diff.sampled) {
                report.result.proofMode = ProofMode.EXACT_DIFF;
                report.result.confidence = ConfidenceLevel.EXACT;
            }
            return;
        }

        if (globalEquivalent && (localization == null || localization.suspiciousScopes == null || localization.suspiciousScopes.isEmpty())) {
            report.result.status = "CONSISTENT";
            report.result.rootCause = null;
            if (report.result.confidence == null) {
                report.result.confidence = ConfidenceLevel.HIGH;
            }
            if (report.result.proofMode == null) {
                report.result.proofMode = ProofMode.EXACT_DIFF;
            }
            return;
        }

        report.result.status = "DIFF_FOUND";
        report.result.rootCause = resolveRootCause(report.evidence.globalSignal, exactDiff == null ? null : exactDiff.diff);
        if (report.result.confidence == null) {
            report.result.confidence = ConfidenceLevel.HIGH;
        }
        if (report.result.proofMode == null) {
            report.result.proofMode = ProofMode.EXACT_DIFF;
        }
    }

    private boolean summaryEquivalent(GlobalSignalEvidence globalSignal) {
        SummaryMetrics sourceSummary = globalSignal == null ? null : globalSignal.sourceSummary;
        SummaryMetrics targetSummary = globalSignal == null ? null : globalSignal.targetSummary;
        return summaryEngine.equivalent(sourceSummary, targetSummary);
    }

    private String resolveRootCause(GlobalSignalEvidence globalSignal, io.github.dataaudit.spi.model.DiffResult diff) {
        boolean rowCountMismatch = globalSignal != null
                && globalSignal.sourceSummary != null
                && globalSignal.targetSummary != null
                && globalSignal.sourceSummary.rowCount != globalSignal.targetSummary.rowCount;
        boolean duplicateOrMissing = hasStructuralMismatch(diff);
        boolean valueMismatch = diff != null && !diff.consistent;
        return rootCauseEngine.resolve(false, rowCountMismatch, duplicateOrMissing, valueMismatch);
    }

    private String resolveSignalOnlyRootCause(GlobalSignalEvidence globalSignal,
                                              LocalizationEvidence localization) {
        boolean rowCountMismatch = globalSignal != null
                && globalSignal.sourceSummary != null
                && globalSignal.targetSummary != null
                && globalSignal.sourceSummary.rowCount != globalSignal.targetSummary.rowCount;
        boolean valueMismatch = localization != null
                && localization.suspiciousScopes != null
                && !localization.suspiciousScopes.isEmpty();
        return rootCauseEngine.resolve(false, rowCountMismatch, false, valueMismatch);
    }

    private boolean hasStructuralMismatch(io.github.dataaudit.spi.model.DiffResult diff) {
        if (diff == null || diff.samples == null) {
            return false;
        }
        for (io.github.dataaudit.spi.model.DiffResult.DiffSample sample : diff.samples) {
            if (sample == null || sample.type == null) {
                continue;
            }
            if ("missing_in_target".equals(sample.type)
                    || "extra_in_target".equals(sample.type)
                    || "multiset_mismatch".equals(sample.type)
                    || "multiset_extra_target".equals(sample.type)) {
                return true;
            }
        }
        return false;
    }

    private void attachExactDiffProgress(ReportModel report,
                                         TaskFileSpec spec,
                                         String runId,
                                         ExactDiffEvidence exactDiff) {
        if (exactDiff == null || exactDiff.progressEvents == null) {
            return;
        }
        for (ProgressEvent event : exactDiff.progressEvents) {
            normalizeProgressEvent(event, spec, runId);
            report.evidence.progressEvents.add(event);
        }
    }

    private void addProgressEvent(ReportModel report,
                                  TaskFileSpec spec,
                                  String runId,
                                  String stage,
                                  String sliceKey,
                                  String status) {
        ProgressEvent event = "started".equals(status)
                ? ProgressEvent.started(spec.task.name, runId, stage, sliceKey)
                : ProgressEvent.completed(spec.task.name, runId, stage, sliceKey);
        report.evidence.progressEvents.add(event);
    }

    private void addLimitEvent(ReportModel report,
                               TaskFileSpec spec,
                               String runId,
                               String stage,
                               String sliceKey,
                               io.github.dataaudit.spi.model.DiffResult diff) {
        report.evidence.progressEvents.add(ProgressEvent.limitExceeded(
                spec.task.name,
                runId,
                stage,
                sliceKey,
                diff.limitType,
                diff.fallbackReason
        ));
    }

    private void normalizeProgressEvent(ProgressEvent event, TaskFileSpec spec, String runId) {
        if (event.taskName == null) {
            event.taskName = spec.task.name;
        }
        if (event.runId == null) {
            event.runId = runId;
        }
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
        report.taskName = spec.task.name;
        report.plan.taskName = spec.task.name;
        report.plan.scaleClass = plan.scaleClass;
        report.plan.signalStrategy = plan.signalStrategy;
        report.plan.localizationStrategy = plan.localizationStrategy;
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

    private List<SliceDescriptor> deduplicateSlices(List<SliceDescriptor> slices) {
        List<SliceDescriptor> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (slices == null) {
            return result;
        }
        for (SliceDescriptor slice : slices) {
            if (slice == null || slice.sliceKey == null || seen.contains(slice.sliceKey)) {
                continue;
            }
            seen.add(slice.sliceKey);
            result.add(slice);
        }
        return result;
    }
}
