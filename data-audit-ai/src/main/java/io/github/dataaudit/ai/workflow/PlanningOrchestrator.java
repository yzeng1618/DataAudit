package io.github.dataaudit.ai.workflow;

import io.github.dataaudit.ai.guardrail.ArtifactStructureValidator;
import io.github.dataaudit.ai.guardrail.AuditPlanGuardrail;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.ai.planner.AiStrategyPlanner;
import io.github.dataaudit.ai.provider.AiClient;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.rag.RagRetriever;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlanningOrchestrator {
    private final AiWorkflowConfig config;
    private final AiClient client;
    private final RagRetriever retriever;
    private final AiStrategyPlanner fallbackPlanner;
    private final AuditPlanGuardrail planGuardrail;
    private final ArtifactStructureValidator structureValidator;

    public PlanningOrchestrator(AiWorkflowConfig config, RagRetriever retriever) {
        this(config, new AiClientFactory().create(config), retriever, new AiStrategyPlanner(),
                new AuditPlanGuardrail(), new ArtifactStructureValidator());
    }

    public PlanningOrchestrator(AiWorkflowConfig config,
                                AiClient client,
                                RagRetriever retriever,
                                AiStrategyPlanner fallbackPlanner,
                                AuditPlanGuardrail planGuardrail,
                                ArtifactStructureValidator structureValidator) {
        this.config = config == null ? new AiWorkflowConfig() : config;
        this.client = client;
        this.retriever = retriever == null ? new LocalCaseRetriever() : retriever;
        this.fallbackPlanner = fallbackPlanner;
        this.planGuardrail = planGuardrail;
        this.structureValidator = structureValidator;
    }

    public AuditPlan plan(TableProfile profile) {
        structureValidator.validate(profile);
        Map<String, Object> features = planningFeatures(profile);
        List<HistoricalCase> cases = retriever.retrieve(features, 5);
        List<String> caseTitles = cases.stream().map(c -> c.id + ":" + c.title).toList();
        if (config.providerEnabled()) {
            try {
                PlanningRequest request = new PlanningRequest(profile, features, cases,
                        AiPromptContracts.PLANNING_CONTRACT);
                AuditPlan proposal = client.generateJson(AiPromptContracts.PLAN_AUDIT, request, AuditPlan.class);
                proposal.retrievedCases.addAll(caseTitles);
                proposal.plannerTrace.aiProvider = client.name();
                proposal.plannerTrace.knowledgeCases.addAll(caseTitles);
                proposal.plannerTrace.decisions.add("provider_proposal:" + client.name());
                return planGuardrail.apply(proposal);
            } catch (Exception e) {
                if (!config.fallbackOnProviderError) {
                    throw new IllegalStateException("AI planning provider failed", e);
                }
                AuditPlan fallback = fallbackPlanner.plan(profile, caseTitles);
                fallback.plannerTrace.aiProvider = "fallback:" + client.name();
                fallback.plannerTrace.guardrailActions.add("provider_failed:" + e.getClass().getSimpleName());
                return fallback;
            }
        }
        AuditPlan fallback = fallbackPlanner.plan(profile, caseTitles);
        fallback.plannerTrace.aiProvider = "disabled";
        fallback.plannerTrace.decisions.add("rule_fallback_provider_disabled");
        return fallback;
    }

    private Map<String, Object> planningFeatures(TableProfile profile) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("source_type", profile.source.type);
        features.put("target_type", profile.target.type);
        features.put("write_mode", profile.syncContext.writeMode);
        features.put("sync_mode", profile.syncContext.syncMode);
        features.put("boundary_type", profile.boundary.type);
        features.put("retrieval_hints", profile.retrievalHints);
        features.put("columns", profile.columns.stream()
                .map(column -> column.name + ":" + column.type)
                .toList());
        features.put("field_types", profile.columns.stream().map(column -> lower(column.type)).toList());
        features.put("profile_missing_information", profile.missingInformation);
        return features;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record PlanningRequest(TableProfile profile,
                                  Map<String, Object> features,
                                  List<HistoricalCase> retrievedCases,
                                  String contract) {
    }
}
