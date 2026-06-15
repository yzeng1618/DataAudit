package io.github.dataaudit.ai.workflow;

import io.github.dataaudit.ai.analysis.AnomalyExtractor;
import io.github.dataaudit.ai.analysis.RootCauseAnalyzer;
import io.github.dataaudit.ai.guardrail.ArtifactStructureValidator;
import io.github.dataaudit.ai.guardrail.RequiredFieldGuardrail;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.provider.AiClient;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.rag.RagRetriever;

import java.util.List;
import java.util.Map;

public class RootCauseOrchestrator {
    private final AiWorkflowConfig config;
    private final AiClient client;
    private final RagRetriever retriever;
    private final AnomalyExtractor extractor;
    private final RootCauseAnalyzer fallbackAnalyzer;
    private final ArtifactStructureValidator structureValidator;
    private final RequiredFieldGuardrail requiredFieldGuardrail;

    public RootCauseOrchestrator(AiWorkflowConfig config, RagRetriever retriever) {
        this(config, new AiClientFactory().create(config), retriever, new AnomalyExtractor(),
                new RootCauseAnalyzer(retriever == null ? new LocalCaseRetriever() : retriever),
                new ArtifactStructureValidator(), new RequiredFieldGuardrail());
    }

    public RootCauseOrchestrator(AiWorkflowConfig config,
                                 AiClient client,
                                 RagRetriever retriever,
                                 AnomalyExtractor extractor,
                                 RootCauseAnalyzer fallbackAnalyzer,
                                 ArtifactStructureValidator structureValidator,
                                 RequiredFieldGuardrail requiredFieldGuardrail) {
        this.config = config == null ? new AiWorkflowConfig() : config;
        this.client = client;
        this.retriever = retriever == null ? new LocalCaseRetriever() : retriever;
        this.extractor = extractor;
        this.fallbackAnalyzer = fallbackAnalyzer;
        this.structureValidator = structureValidator;
        this.requiredFieldGuardrail = requiredFieldGuardrail;
    }

    public RootCauseAnalysis analyze(AuditPlan plan, Map<String, Object> auditResult) {
        Map<String, Object> features = extractor.extract(plan, auditResult);
        List<HistoricalCase> cases = retriever.retrieve(features, 5);
        if (config.providerEnabled()) {
            try {
                AnalysisRequest request = new AnalysisRequest(plan, auditResult, features, cases,
                        AiPromptContracts.ANALYSIS_CONTRACT);
                RootCauseAnalysis proposal = client.generateJson(AiPromptContracts.ANALYZE_ROOT_CAUSE,
                        request,
                        RootCauseAnalysis.class);
                enrichRetrievedEvidence(proposal, retriever.retrieveSummaries(features, 5));
                structureValidator.validate(proposal);
                requiredFieldGuardrail.validate(proposal);
                return proposal;
            } catch (Exception e) {
                if (!config.fallbackOnProviderError) {
                    throw new IllegalStateException("AI analysis provider failed", e);
                }
            }
        }
        return fallbackAnalyzer.analyze(plan, auditResult);
    }

    public record AnalysisRequest(AuditPlan plan,
                                  Map<String, Object> auditResult,
                                  Map<String, Object> features,
                                  List<HistoricalCase> retrievedCases,
                                  String contract) {
    }

    private void enrichRetrievedEvidence(RootCauseAnalysis proposal,
                                          List<RootCauseAnalysis.RetrievedCase> retrievedSummaries) {
        if (proposal.retrievedCases.isEmpty()) {
            proposal.retrievedCases.addAll(retrievedSummaries);
        }
        for (RootCauseAnalysis.PossibleCause cause : proposal.possibleRootCauses) {
            if (cause.retrievedCases.isEmpty()) {
                cause.retrievedCases.addAll(proposal.retrievedCases);
            }
            for (RootCauseAnalysis.RetrievedCase retrievedCase : cause.retrievedCases) {
                if (retrievedCase.id != null && !retrievedCase.id.isBlank()) {
                    cause.evidence.add("retrieved_case:" + retrievedCase.id + " matched_evidence="
                            + String.join("|", retrievedCase.matchedEvidence));
                }
            }
        }
    }
}
