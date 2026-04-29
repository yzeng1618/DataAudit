package io.github.dataaudit.ai.planner;

import io.github.dataaudit.ai.guardrail.RequiredFieldGuardrail;
import io.github.dataaudit.ai.guardrail.SqlSafetyChecker;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.TableProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AiStrategyPlanner {
    private final SqlSafetyChecker sqlSafetyChecker;
    private final RequiredFieldGuardrail requiredFieldGuardrail;

    public AiStrategyPlanner() {
        this(new SqlSafetyChecker(), new RequiredFieldGuardrail());
    }

    public AiStrategyPlanner(SqlSafetyChecker sqlSafetyChecker, RequiredFieldGuardrail requiredFieldGuardrail) {
        this.sqlSafetyChecker = sqlSafetyChecker;
        this.requiredFieldGuardrail = requiredFieldGuardrail;
    }

    public AuditPlan plan(TableProfile profile, List<String> retrievedCases) {
        AuditPlan plan = new AuditPlan();
        plan.syncContext = profile.syncContext;
        plan.retrievedCases.addAll(retrievedCases == null ? List.of() : retrievedCases);
        plan.plannerTrace.knowledgeCases.addAll(plan.retrievedCases);
        classify(profile, plan);
        recognizeSemantics(profile, plan);
        recognizeRisks(profile, plan);
        recommendSteps(profile, plan);
        applyKnowledgeHook(plan);
        ensureBaselineChecks(plan);
        applySqlGuardrail(plan);
        plan.missingInformation.addAll(profile.missingInformation);
        if (profile.samples.isEmpty()) {
            plan.missingInformation.add("samples_not_available");
        }
        if (profile.statistics.estimatedRows == null) {
            plan.missingInformation.add("estimated_rows_not_available");
        }
        plan.plannerTrace.decisions.add("AI-first semantics and rule guardrails produced deterministic check recommendations");
        requiredFieldGuardrail.validate(plan);
        return plan;
    }

    private void classify(TableProfile profile, AuditPlan plan) {
        long rows = profile.statistics.estimatedRows == null ? -1L : profile.statistics.estimatedRows;
        String scale = rows < 0 ? "unknown" : rows < 1_000_000L ? "small" : rows < 100_000_000L ? "large" : "xlarge";
        boolean partitioned = !profile.overrides.partitionFields.isEmpty()
                || profile.columns.stream().anyMatch(column -> isPartitionName(column.name));
        plan.tableClassification.scaleClass = scale;
        plan.tableClassification.tableType = (partitioned ? scale + "_partitioned_table" : scale + "_table")
                .replace("unknown_", "");
        plan.tableClassification.confidence = rows < 0 ? 0.66 : 0.88;
        plan.tableClassification.evidence.add(rows < 0 ? "estimated_rows_missing" : "estimated_rows=" + rows);
        if (partitioned) {
            plan.tableClassification.evidence.add("partition_field_detected");
        }
        plan.tableClassification.missingInformation.add(rows < 0 ? "estimated_rows" : "none");
    }

    private void recognizeSemantics(TableProfile profile, AuditPlan plan) {
        addAllDistinct(plan.semanticAnalysis.candidatePrimaryKeys, profile.overrides.primaryKeys);
        addAllDistinct(plan.semanticAnalysis.partitionFields, profile.overrides.partitionFields);
        for (TableProfile.ColumnProfile column : profile.columns) {
            String name = lower(column.name);
            String type = lower(column.type);
            if (isKeyCandidate(name)) {
                addDistinct(plan.semanticAnalysis.candidatePrimaryKeys, column.name);
                addInsight(plan, column.name, "candidate_primary_key", 0.82, "field_name_pattern");
            }
            if (isPartitionName(name)) {
                addDistinct(plan.semanticAnalysis.partitionFields, column.name);
                addInsight(plan, column.name, "partition_field", 0.84, "field_name_pattern");
            }
            if (isTimeField(name, type)) {
                addDistinct(plan.semanticAnalysis.timeFields, column.name);
                addInsight(plan, column.name, "time_field", 0.83, "name_or_type_temporal");
            }
            if (isMetricField(name, type)) {
                addDistinct(plan.semanticAnalysis.metricFields, column.name);
                addInsight(plan, column.name, "metric_field", 0.86, "name_or_type_numeric");
            }
            if (isEnumField(name)) {
                addDistinct(plan.semanticAnalysis.enumFields, column.name);
                addInsight(plan, column.name, "enum_field", 0.8, "field_name_pattern");
            }
            if (type.contains("char") || type.contains("string") || type.contains("text")) {
                addDistinct(plan.semanticAnalysis.textFields, column.name);
            }
            if (isSemiStructured(name, type)) {
                addDistinct(plan.semanticAnalysis.semiStructuredFields, column.name);
                addInsight(plan, column.name, "semi_structured_field", 0.86, "json_variant_map_type_or_name");
            }
        }
    }

    private void recognizeRisks(TableProfile profile, AuditPlan plan) {
        for (TableProfile.ColumnProfile column : profile.columns) {
            String name = lower(column.name);
            String type = lower(column.type);
            if (type.contains("decimal") || type.contains("number") || type.contains("numeric")) {
                addRisk(plan, column.name, "decimal_precision_risk", "decimal precision or scale may drift across engines",
                        0.82, "numeric_type:" + column.type, profile.overrides.decimalScale.containsKey(column.name) ? "none" : "normalize.decimal_scale");
            }
            if (isTimeField(name, type) && (profile.syncContext.timezone == null || profile.syncContext.timezone.isBlank())) {
                addRisk(plan, column.name, "timestamp_timezone_risk", "timestamp values may shift across timezone boundaries",
                        0.78, "temporal_field:" + column.name, "normalize.timezone");
            }
            if (isPartitionName(name)) {
                addRisk(plan, column.name, "partition_shift_risk", "partition value may shift because of date or timezone conversion",
                        0.79, "partition_field:" + column.name, "source_target_partition_metadata");
            }
            if (isSemiStructured(name, type)) {
                addRisk(plan, column.name, "semi_structured_schema_drift_risk", "JSON/Variant/Map schema may drift between source and target",
                        0.84, "semi_structured_field:" + column.name, "known_json_paths");
            }
        }
        if ("overwrite".equalsIgnoreCase(profile.syncContext.writeMode)) {
            addRisk(plan, firstOrDefault(plan.semanticAnalysis.partitionFields, "*"), "partition_overwrite_risk",
                    "overwrite write mode may cover an incomplete partition range", 0.86,
                    "write_mode=overwrite", "target_snapshot_or_commit_files");
        }
    }

    private void recommendSteps(TableProfile profile, AuditPlan plan) {
        addStep(plan, "global_row_count", "global_row_count", "全局行数核验",
                "always", "select count(*) as row_count from ${table}",
                "SUPPORTED", "summary_engine", 0.97, "deterministic_baseline", false);
        addStep(plan, "global_checksum", "global_checksum", "全局 checksum 核验",
                "always", "select checksum(${columns}) as checksum from ${table}",
                "SUPPORTED", "summary_engine", 0.94, "deterministic_baseline", false);
        for (String partition : plan.semanticAnalysis.partitionFields) {
            addStep(plan, "partition_row_count_" + partition, "partition_row_count", "按分区行数核验: " + partition,
                    "partition field detected", "select " + partition + ", count(*) from ${table} group by " + partition,
                    "SUPPORTED", "grouped_signal", 0.9, "partition_field:" + partition,
                    !profile.overrides.partitionFields.contains(partition));
            addStep(plan, "partition_checksum_" + partition, "partition_checksum", "按分区 checksum 核验: " + partition,
                    "partition field detected", "select " + partition + ", checksum(${columns}) from ${table} group by " + partition,
                    "SUPPORTED", "grouped_checksum", 0.88, "partition_field:" + partition,
                    !profile.overrides.partitionFields.contains(partition));
        }
        for (String metric : plan.semanticAnalysis.metricFields) {
            addStep(plan, "metric_sum_" + metric, "metric_sum", "指标字段 SUM/MIN/MAX 核验: " + metric,
                    "metric field detected", "select sum(" + metric + "), min(" + metric + "), max(" + metric + ") from ${table}",
                    "PARTIAL", "custom_metric_template", 0.84, "metric_field:" + metric, false);
        }
        for (String enumField : plan.semanticAnalysis.enumFields) {
            addStep(plan, "enum_distribution_" + enumField, "enum_distribution", "枚举分布核验: " + enumField,
                    "enum field detected", "select " + enumField + ", count(*) from ${table} group by " + enumField,
                    "PARTIAL", "custom_distribution_template", 0.82, "enum_field:" + enumField, false);
        }
        for (String timeField : plan.semanticAnalysis.timeFields) {
            addStep(plan, "time_min_max_" + timeField, "time_min_max", "时间边界 min/max 核验: " + timeField,
                    "time field detected", "select min(" + timeField + "), max(" + timeField + ") from ${table}",
                    "PARTIAL", "custom_temporal_template", 0.83, "time_field:" + timeField, false);
        }
        for (String field : plan.semanticAnalysis.semiStructuredFields) {
            addStep(plan, "json_schema_drift_" + field, "json_schema_drift", "半结构化字段 schema drift 核验: " + field,
                    "semi-structured field detected", "select count(*) from ${table} where " + field + " is null",
                    "UNSUPPORTED", "manual_json_template", 0.78, "semi_structured_field:" + field, false);
        }
        for (String key : plan.semanticAnalysis.candidatePrimaryKeys) {
            addStep(plan, "duplicate_key_" + key, "duplicate_key", "候选主键重复检查: " + key,
                    "candidate key detected", "select " + key + ", count(*) from ${table} group by " + key + " having count(*) > 1",
                    "PARTIAL", "precheck_template", 0.81, "candidate_key:" + key,
                    !profile.overrides.primaryKeys.contains(key));
            break;
        }
        if (!plan.semanticAnalysis.partitionFields.isEmpty() || !plan.semanticAnalysis.candidatePrimaryKeys.isEmpty()) {
            addStep(plan, "bucket_diff_for_abnormal_partition", "bucket_diff", "异常分区或候选 key 的 bucket diff",
                    "abnormal partition or grouped checksum mismatch", "data-audit diff -f task.yaml --slice ${slice}",
                    "SUPPORTED", "diff_command", 0.86, "localization_followup", false);
        }
    }

    private void applyKnowledgeHook(AuditPlan plan) {
        for (String caseTitle : plan.retrievedCases) {
            String lower = lower(caseTitle);
            if (lower.contains("overwrite") && plan.riskAnalysis.stream()
                    .noneMatch(risk -> "partition_overwrite_risk".equals(risk.riskType))) {
                addRisk(plan, firstOrDefault(plan.semanticAnalysis.partitionFields, "*"), "partition_overwrite_risk",
                        "retrieved case indicates overwrite partition coverage risk", 0.76,
                        "retrieved_case:" + caseTitle, "write_mode_or_commit_log");
            }
            if (lower.contains("embedding") && plan.recommendedSteps.stream()
                    .noneMatch(step -> "embedding_dimension_check".equals(step.type))) {
                addStep(plan, "embedding_dimension_check", "embedding_dimension_check", "RAG embedding_dim 一致性检查",
                        "embedding/vector field or retrieved case detected", "select embedding_dim, count(*) from ${table} group by embedding_dim",
                        "UNSUPPORTED", "manual_vector_check", 0.77, "retrieved_case:" + caseTitle, false);
            }
            if ((lower.contains("decimal") || lower.contains("precision")) && plan.recommendedSteps.stream()
                    .noneMatch(step -> "decimal_precision_check".equals(step.type))) {
                String field = firstOrDefault(plan.semanticAnalysis.metricFields, "decimal_field");
                addStep(plan, "decimal_precision_check_" + field, "decimal_precision_check",
                        "Decimal precision/scale drift check: " + field,
                        "retrieved decimal precision case or decimal field detected",
                        "select scale(" + field + "), count(*) from ${table} group by scale(" + field + ")",
                        "PARTIAL", "custom_decimal_template", 0.8, "retrieved_case:" + caseTitle, false);
            }
            if ((lower.contains("cdc") || lower.contains("boundary")) && plan.recommendedSteps.stream()
                    .noneMatch(step -> "incremental_boundary_check".equals(step.type))) {
                String field = firstOrDefault(plan.semanticAnalysis.timeFields, "update_time");
                addStep(plan, "incremental_boundary_check_" + field, "incremental_boundary_check",
                        "CDC/incremental boundary check: " + field,
                        "retrieved CDC boundary case or sync mode indicates incremental context",
                        "select min(" + field + "), max(" + field + ") from ${table}",
                        "PARTIAL", "custom_boundary_template", 0.78, "retrieved_case:" + caseTitle, false);
            }
            if ((lower.contains("doris") || lower.contains("redirect") || lower.contains("retry")) && plan.recommendedSteps.stream()
                    .noneMatch(step -> "sink_retry_log_check".equals(step.type))) {
                addStep(plan, "sink_retry_log_check", "sink_retry_log_check",
                        "Sink retry/redirect log check",
                        "retrieved sink retry or redirect case",
                        null,
                        "UNSUPPORTED", "manual_log_inspection", 0.75, "retrieved_case:" + caseTitle, false);
            }
        }
    }

    private void ensureBaselineChecks(AuditPlan plan) {
        if (plan.recommendedSteps.stream().noneMatch(step -> "global_row_count".equals(step.type))) {
            addStep(plan, "global_row_count", "global_row_count", "全局行数核验",
                    "guardrail baseline", "select count(*) as row_count from ${table}",
                    "SUPPORTED", "summary_engine", 0.97, "guardrail_inserted", false);
            plan.plannerTrace.guardrailActions.add("inserted global_row_count");
        }
        if (plan.recommendedSteps.stream().noneMatch(step -> "global_checksum".equals(step.type))) {
            addStep(plan, "global_checksum", "global_checksum", "全局 checksum 核验",
                    "guardrail baseline", "select checksum(${columns}) as checksum from ${table}",
                    "SUPPORTED", "summary_engine", 0.94, "guardrail_inserted", false);
            plan.plannerTrace.guardrailActions.add("inserted global_checksum");
        }
    }

    private void applySqlGuardrail(AuditPlan plan) {
        List<AuditPlan.RecommendedStep> safe = new ArrayList<>();
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            if (sqlSafetyChecker.isSafe(step.sqlTemplate) && sqlSafetyChecker.isSafe(step.dslTemplate)) {
                safe.add(step);
            } else {
                plan.plannerTrace.guardrailActions.add("removed unsafe step:" + step.id);
            }
        }
        plan.recommendedSteps.clear();
        plan.recommendedSteps.addAll(safe);
        plan.dataAuditMapping.clear();
        for (AuditPlan.RecommendedStep step : plan.recommendedSteps) {
            plan.dataAuditMapping.add(step.mapping);
        }
    }

    private void addStep(AuditPlan plan, String id, String type, String description, String trigger,
                         String sql, String status, String capability, double confidence, String evidence,
                         boolean requiresUserConfirmation) {
        AuditPlan.RecommendedStep step = new AuditPlan.RecommendedStep();
        step.id = id;
        step.type = type;
        step.description = description;
        step.triggerCondition = trigger;
        step.sqlTemplate = sql;
        step.confidence = confidence;
        step.evidence.add(evidence);
        step.missingInformation.add("none");
        step.requiresUserConfirmation = requiresUserConfirmation;
        if (requiresUserConfirmation) {
            step.riskPoints.add("requires_user_confirmation before writing task config");
        }
        step.mapping.stepId = id;
        step.mapping.capability = capability;
        step.mapping.status = status;
        step.mapping.notes = status.equals("SUPPORTED") ? "can map to existing DataAudit deterministic capability"
                : "template recommendation; deterministic execution requires explicit support or manual check";
        step.mapping.executionPlanRefs.add("TaskFileSpec");
        step.mapping.executionPlanRefs.add("ExecutionPlan");
        plan.recommendedSteps.add(step);
        plan.dataAuditMapping.add(step.mapping);
    }

    private void addRisk(AuditPlan plan, String field, String type, String description, double confidence,
                         String evidence, String missing) {
        AuditPlan.RiskItem risk = new AuditPlan.RiskItem();
        risk.field = field;
        risk.riskType = type;
        risk.description = description;
        risk.confidence = confidence;
        risk.evidence.add(evidence);
        risk.missingInformation.add(missing == null || missing.isBlank() ? "none" : missing);
        plan.riskAnalysis.add(risk);
    }

    private void addInsight(AuditPlan plan, String field, String type, double confidence, String evidence) {
        AuditPlan.FieldInsight insight = new AuditPlan.FieldInsight();
        insight.field = field;
        insight.semanticType = type;
        insight.confidence = confidence;
        insight.evidence.add(evidence);
        insight.missingInformation.add("stats_sample_optional");
        plan.semanticAnalysis.fieldInsights.add(insight);
    }

    private boolean isKeyCandidate(String name) {
        return "id".equals(name) || name.endsWith("_id") || name.endsWith("_no") || name.endsWith("_key");
    }

    private boolean isPartitionName(String name) {
        return "dt".equals(name) || "ds".equals(name) || name.startsWith("partition") || name.endsWith("_date");
    }

    private boolean isTimeField(String name, String type) {
        return name.contains("time") || name.endsWith("_date") || "dt".equals(name) || type.contains("timestamp") || type.contains("date");
    }

    private boolean isMetricField(String name, String type) {
        return name.contains("amount") || name.contains("price") || name.contains("cost")
                || name.contains("fee") || name.contains("balance") || name.contains("total")
                || type.contains("decimal") || type.contains("numeric") || type.contains("number");
    }

    private boolean isEnumField(String name) {
        return name.contains("status") || name.contains("state") || name.endsWith("_type")
                || name.contains("category") || name.contains("flag");
    }

    private boolean isSemiStructured(String name, String type) {
        return type.contains("json") || type.contains("variant") || type.contains("map") || type.contains("struct")
                || name.contains("payload") || name.contains("extra") || name.contains("properties");
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String firstOrDefault(List<String> values, String fallback) {
        return values.isEmpty() ? fallback : values.get(0);
    }

    private void addDistinct(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private void addAllDistinct(List<String> values, List<String> candidates) {
        Set<String> distinct = new LinkedHashSet<>(values);
        distinct.addAll(candidates);
        values.clear();
        values.addAll(distinct);
    }
}
