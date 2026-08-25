// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class AuditPlan {
    public String artifactVersion = "1";
    public String artifactType = "ai_audit_plan";
    public String producer = "data-audit-ai";
    public String schemaVersion = "data-audit-ai-audit-plan-v1";
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public String planVersion = "alpha-1";
    public TableClassification tableClassification = new TableClassification();
    public SemanticAnalysis semanticAnalysis = new SemanticAnalysis();
    public List<RiskItem> riskAnalysis = new ArrayList<>();
    public List<RecommendedStep> recommendedSteps = new ArrayList<>();
    public List<DataAuditMapping> dataAuditMapping = new ArrayList<>();
    public PlannerTrace plannerTrace = new PlannerTrace();
    public TableProfile.SyncContext syncContext = new TableProfile.SyncContext();
    public DeterministicBoundary deterministicBoundary = new DeterministicBoundary();
    public List<String> retrievedCases = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();

    public static class TableClassification {
        public String tableType;
        public String scaleClass;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
    }

    public static class SemanticAnalysis {
        public List<String> candidatePrimaryKeys = new ArrayList<>();
        public List<String> partitionFields = new ArrayList<>();
        public List<String> timeFields = new ArrayList<>();
        public List<String> metricFields = new ArrayList<>();
        public List<String> enumFields = new ArrayList<>();
        public List<String> textFields = new ArrayList<>();
        public List<String> semiStructuredFields = new ArrayList<>();
        public List<FieldInsight> fieldInsights = new ArrayList<>();
    }

    public static class FieldInsight {
        public String field;
        public String semanticType;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
    }

    public static class RiskItem {
        public String field;
        public String riskType;
        public String description;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
    }

    public static class RecommendedStep {
        public String id;
        public String type;
        public String description;
        public String triggerCondition;
        public String sqlTemplate;
        public String dslTemplate;
        public List<String> riskPoints = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public DataAuditMapping mapping = new DataAuditMapping();
        public boolean requiresUserConfirmation;
    }

    public static class DataAuditMapping {
        public String stepId;
        public String capability;
        public String status;
        public String notes;
        public List<String> executionPlanRefs = new ArrayList<>();
    }

    public static class PlannerTrace {
        public String aiProvider = "disabled";
        public List<String> decisions = new ArrayList<>();
        public List<String> guardrailActions = new ArrayList<>();
        public List<String> knowledgeCases = new ArrayList<>();
    }

    public static class DeterministicBoundary {
        public boolean aiConsistencyConclusion = false;
        public String notice = "AI only recommends deterministic checks; consistency status must come from DataAudit audit results.";
    }
}
