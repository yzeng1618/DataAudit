package io.github.dataaudit.ai.guardrail;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonSchemas {
    private JsonSchemas() {
    }

    public static Map<String, Object> schemaFor(Class<?> responseType) {
        String name = responseType == null ? "" : responseType.getSimpleName();
        return switch (name) {
            case "AuditPlan" -> auditPlanSchema();
            case "RootCauseAnalysis" -> rootCauseSchema();
            case "MarkdownReportRewrite" -> markdownRewriteSchema();
            case "TableProfile" -> tableProfileSchema();
            default -> objectSchema(name.isBlank() ? "unknown" : name, List.of(), Map.of());
        };
    }

    public static List<String> auditPlanRequiredSections() {
        return List.of(
                "table_classification",
                "semantic_analysis",
                "risk_analysis",
                "recommended_steps",
                "data_audit_mapping",
                "planner_trace",
                "missing_information");
    }

    public static List<String> rootCauseRequiredSections() {
        return List.of(
                "anomaly_summary",
                "possible_root_causes",
                "retrieved_cases",
                "recommended_checks",
                "missing_information",
                "ai_safety_notice");
    }

    public static List<String> tableProfileRequiredSections() {
        return List.of(
                "source",
                "target",
                "columns",
                "statistics",
                "samples",
                "sync_context",
                "boundary",
                "overrides",
                "missing_information");
    }

    private static Map<String, Object> auditPlanSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("table_classification", objectSchema("TableClassification",
                List.of("table_type", "scale_class", "confidence", "evidence", "missing_information"),
                Map.of(
                        "table_type", stringSchema(),
                        "scale_class", stringSchema(),
                        "confidence", numberSchema(),
                        "evidence", stringArraySchema(),
                        "missing_information", stringArraySchema())));
        properties.put("semantic_analysis", objectSchema("SemanticAnalysis",
                List.of("candidate_primary_keys", "partition_fields", "time_fields", "metric_fields", "field_insights"),
                Map.of(
                        "candidate_primary_keys", stringArraySchema(),
                        "partition_fields", stringArraySchema(),
                        "time_fields", stringArraySchema(),
                        "metric_fields", stringArraySchema(),
                        "enum_fields", stringArraySchema(),
                        "text_fields", stringArraySchema(),
                        "semi_structured_fields", stringArraySchema(),
                        "field_insights", arraySchema(objectSchema("FieldInsight",
                                List.of("field", "semantic_type", "confidence", "evidence", "missing_information"),
                                Map.of("field", stringSchema(),
                                        "semantic_type", stringSchema(),
                                        "confidence", numberSchema(),
                                        "evidence", stringArraySchema(),
                                        "missing_information", stringArraySchema()))))));
        properties.put("risk_analysis", arraySchema(objectSchema("RiskItem",
                List.of("risk_type", "confidence", "evidence", "missing_information"),
                Map.of(
                        "field", stringSchema(),
                        "risk_type", stringSchema(),
                        "description", stringSchema(),
                        "confidence", numberSchema(),
                        "evidence", stringArraySchema(),
                        "missing_information", stringArraySchema()))));
        properties.put("recommended_steps", arraySchema(stepSchema()));
        properties.put("data_audit_mapping", arraySchema(mappingSchema()));
        properties.put("planner_trace", objectSchema("PlannerTrace",
                List.of("ai_provider", "decisions", "guardrail_actions", "knowledge_cases"),
                Map.of("ai_provider", stringSchema(),
                        "decisions", stringArraySchema(),
                        "guardrail_actions", stringArraySchema(),
                        "knowledge_cases", stringArraySchema())));
        properties.put("sync_context", objectSchema("SyncContext", List.of(), Map.of()));
        properties.put("deterministic_boundary", objectSchema("DeterministicBoundary",
                List.of("ai_consistency_conclusion", "notice"),
                Map.of("ai_consistency_conclusion", booleanSchema(), "notice", stringSchema())));
        properties.put("retrieved_cases", stringArraySchema());
        properties.put("missing_information", stringArraySchema());
        return objectSchema("AuditPlan", auditPlanRequiredSections(), properties);
    }

    private static Map<String, Object> rootCauseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("anomaly_summary", stringSchema());
        properties.put("possible_root_causes", arraySchema(objectSchema("PossibleCause",
                List.of("hypothesis", "confidence", "evidence", "recommended_checks", "missing_information"),
                Map.of(
                        "hypothesis", stringSchema(),
                        "confidence", numberSchema(),
                        "evidence", stringArraySchema(),
                        "retrieved_cases", arraySchema(retrievedCaseSchema()),
                        "recommended_checks", stringArraySchema(),
                        "missing_information", stringArraySchema()))));
        properties.put("retrieved_cases", arraySchema(retrievedCaseSchema()));
        properties.put("recommended_checks", stringArraySchema());
        properties.put("missing_information", stringArraySchema());
        properties.put("ai_safety_notice", stringSchema());
        return objectSchema("RootCauseAnalysis", rootCauseRequiredSections(), properties);
    }

    private static Map<String, Object> tableProfileSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", objectSchema("EndpointProfile", List.of("type"), Map.of()));
        properties.put("target", objectSchema("EndpointProfile", List.of("type"), Map.of()));
        properties.put("columns", arraySchema(objectSchema("ColumnProfile",
                List.of("name", "type"),
                Map.of("name", stringSchema(), "type", stringSchema()))));
        properties.put("statistics", objectSchema("StatisticsProfile", List.of(), Map.of()));
        properties.put("samples", arraySchema(objectSchema("SampleProfile",
                List.of("field", "masked", "pattern", "hash", "summary"),
                Map.of("field", stringSchema(),
                        "masked", booleanSchema(),
                        "pattern", stringSchema(),
                        "hash", stringSchema(),
                        "summary", stringSchema()))));
        properties.put("sync_context", objectSchema("SyncContext", List.of(), Map.of()));
        properties.put("boundary", objectSchema("BoundaryProfile", List.of(), Map.of()));
        properties.put("overrides", objectSchema("Overrides", List.of(), Map.of()));
        properties.put("missing_information", stringArraySchema());
        return objectSchema("TableProfile", tableProfileRequiredSections(), properties);
    }

    private static Map<String, Object> markdownRewriteSchema() {
        return objectSchema("MarkdownReportRewrite",
                List.of("markdown"),
                Map.of("markdown", stringSchema(),
                        "notes", stringArraySchema()));
    }

    private static Map<String, Object> stepSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", stringSchema());
        properties.put("type", stringSchema());
        properties.put("description", stringSchema());
        properties.put("trigger_condition", stringSchema());
        properties.put("sql_template", stringSchema());
        properties.put("dsl_template", stringSchema());
        properties.put("risk_points", stringArraySchema());
        properties.put("confidence", numberSchema());
        properties.put("evidence", stringArraySchema());
        properties.put("missing_information", stringArraySchema());
        properties.put("mapping", mappingSchema());
        return objectSchema("RecommendedStep",
                List.of("id", "type", "description", "trigger_condition", "confidence", "evidence", "missing_information", "mapping"),
                properties);
    }

    private static Map<String, Object> mappingSchema() {
        return objectSchema("DataAuditMapping",
                List.of("step_id", "capability", "status"),
                Map.of("step_id", stringSchema(),
                        "capability", stringSchema(),
                        "status", enumSchema("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
                        "notes", stringSchema(),
                        "execution_plan_refs", stringArraySchema()));
    }

    private static Map<String, Object> retrievedCaseSchema() {
        return objectSchema("RetrievedCase",
                List.of("id", "title", "score", "matched_evidence"),
                Map.of("id", stringSchema(),
                        "title", stringSchema(),
                        "score", numberSchema(),
                        "matched_evidence", stringArraySchema()));
    }

    private static Map<String, Object> objectSchema(String title, List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", title);
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> arraySchema(Map<String, Object> itemSchema) {
        return Map.of("type", "array", "items", itemSchema);
    }

    private static Map<String, Object> stringArraySchema() {
        return arraySchema(stringSchema());
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> numberSchema() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
