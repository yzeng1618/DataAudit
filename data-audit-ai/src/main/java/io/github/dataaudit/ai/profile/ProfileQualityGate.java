// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.ProfileReview;
import io.github.dataaudit.ai.model.TableProfile;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class ProfileQualityGate {
    public ProfileReview evaluate(TableProfile profile) {
        ProfileReview review = new ProfileReview();
        if (missingEndpoint(profile.source) || missingEndpoint(profile.target) || profile.columns.isEmpty()) {
            review.status = ProfileReview.Status.INSUFFICIENT;
            review.evidenceQuality = "low";
            if (missingEndpoint(profile.source)) {
                review.missingInformation.add("source.type and source.table/source.query are required");
            }
            if (missingEndpoint(profile.target)) {
                review.missingInformation.add("target.type and target.table/target.query are required");
            }
            if (profile.columns.isEmpty()) {
                review.missingInformation.add("columns are required for AI strategy planning");
            }
            review.nextActions.add("补充 task.yaml 中的 source/target/object.columns 后重跑");
            return review;
        }

        addCandidateKeyReview(profile, review);
        addPartitionReview(profile, review);
        addLowConfidenceSyncReview(profile, review);
        addWriteModeReview(profile, review);
        addSyncModeReview(profile, review);
        addConflictReviews(profile, review);

        if (!review.confirmationItems.isEmpty()) {
            review.status = ProfileReview.Status.REVIEW_REQUIRED;
            review.evidenceQuality = "medium";
            review.nextActions.add("更新 task.yaml overrides 后重跑");
            review.nextActions.add("或使用 --accept-profile 接受当前画像继续生成计划");
        } else {
            review.status = ProfileReview.Status.CONFIRMED;
            review.evidenceQuality = "high";
            review.nextActions.add("画像已确认，可直接生成 audit_plan.json");
        }
        return review;
    }

    private void addCandidateKeyReview(TableProfile profile, ProfileReview review) {
        if (!profile.overrides.primaryKeys.isEmpty()) {
            return;
        }
        for (TableProfile.ColumnProfile column : profile.columns) {
            String name = lower(column.name);
            if ("id".equals(name) || name.endsWith("_id") || name.endsWith("_no") || name.endsWith("_key")) {
                ProfileReview.ConfirmationItem item = new ProfileReview.ConfirmationItem();
                item.field = "candidate_key";
                item.suggestedValue = column.name;
                item.confidence = 0.78;
                item.evidence.add("field_name_pattern:" + column.name);
                item.missingInformation.add("unique_or_distinct_count_evidence");
                item.impact = "影响 exact diff、bucket diff 和重复主键检查的执行路径";
                item.nextAction = "如果该字段是业务主键，写入 object.key；否则使用 --accept-profile 继续";
                review.confirmationItems.add(item);
                review.impact.add(item.impact);
                return;
            }
        }
    }

    private void addPartitionReview(TableProfile profile, ProfileReview review) {
        if (!profile.overrides.partitionFields.isEmpty()) {
            return;
        }
        for (TableProfile.ColumnProfile column : profile.columns) {
            String name = lower(column.name);
            if ("dt".equals(name) || "ds".equals(name) || name.endsWith("_date")) {
                ProfileReview.ConfirmationItem item = new ProfileReview.ConfirmationItem();
                item.field = "partition";
                item.suggestedValue = column.name;
                item.confidence = 0.74;
                item.evidence.add("field_name_pattern:" + column.name);
                item.missingInformation.add("connector_partition_metadata");
                item.impact = "影响分区 row_count/checksum 和异常分区定位";
                item.nextAction = "如果该字段是分区字段，写入 object.partition_by";
                review.confirmationItems.add(item);
                review.impact.add(item.impact);
                return;
            }
        }
    }

    private void addLowConfidenceSyncReview(TableProfile profile, ProfileReview review) {
        if (profile.syncContext.writeMode == null && "overwrite".equalsIgnoreCase(profile.overrides.writeMode)) {
            profile.syncContext.writeMode = profile.overrides.writeMode;
        }
        if (profile.syncContext.timezone == null && profile.overrides.timezone == null
                && hasTimestampField(profile)) {
            ProfileReview.ConfirmationItem item = new ProfileReview.ConfirmationItem();
            item.field = "timezone";
            item.suggestedValue = "UTC";
            item.confidence = 0.62;
            item.evidence.add("timestamp_field_present");
            item.missingInformation.add("normalize.timezone");
            item.impact = "影响 timestamp min/max 和时区偏移风险检查";
            item.nextAction = "在 normalize.timezone 中声明任务边界时区";
            review.confirmationItems.add(item);
            review.impact.add(item.impact);
        }
    }

    private void addWriteModeReview(TableProfile profile, ProfileReview review) {
        if (isBlank(profile.syncContext.writeMode) && isBlank(profile.overrides.writeMode)) {
            addConfirmation(review,
                    "write_mode",
                    "batch|cdc|overwrite|append|merge",
                    0.55,
                    List.of("write_mode_missing"),
                    List.of("semantics.ai.write_mode"),
                    "影响 overwrite 分区风险、CDC 边界和 full-refresh 核验策略",
                    "在 semantics.ai.write_mode 中声明写入模式，或使用 --accept-profile 继续");
        }
    }

    private void addSyncModeReview(TableProfile profile, ProfileReview review) {
        if (isBlank(profile.syncContext.syncMode) && isBlank(profile.overrides.syncMode)) {
            addConfirmation(review,
                    "sync_mode",
                    "batch|cdc|full_refresh|incremental",
                    0.55,
                    List.of("sync_mode_missing"),
                    List.of("semantics.ai.sync_mode"),
                    "影响增量边界、checkpoint 和 source/sink records 分析策略",
                    "在 semantics.ai.sync_mode 中声明同步模式，或使用 --accept-profile 继续");
        }
    }

    private void addConflictReviews(TableProfile profile, ProfileReview review) {
        List<String> inferredKeys = listMetadata(profile, "inferred_primary_keys");
        if (!profile.overrides.primaryKeys.isEmpty() && !inferredKeys.isEmpty()
                && inferredKeys.stream().noneMatch(profile.overrides.primaryKeys::contains)) {
            addConfirmation(review,
                    "primary_key_conflict",
                    String.join(",", profile.overrides.primaryKeys),
                    0.9,
                    List.of("explicit_config:" + profile.overrides.primaryKeys, "inferred:" + inferredKeys),
                    List.of("confirm authoritative object.key"),
                    "影响 exact diff、bucket diff 和重复主键检查的 key 选择",
                    "确认 task.yaml 中 object.key 是否为权威配置");
        }
        List<String> inferredPartitions = listMetadata(profile, "inferred_partition_fields");
        if (!profile.overrides.partitionFields.isEmpty() && !inferredPartitions.isEmpty()
                && inferredPartitions.stream().noneMatch(profile.overrides.partitionFields::contains)) {
            addConfirmation(review,
                    "partition_conflict",
                    String.join(",", profile.overrides.partitionFields),
                    0.9,
                    List.of("explicit_config:" + profile.overrides.partitionFields, "inferred:" + inferredPartitions),
                    List.of("confirm authoritative object.partition_by"),
                    "影响分区 row_count/checksum 和异常范围定位",
                    "确认 task.yaml 中 object.partition_by 是否为权威配置");
        }
        addStringConflict(profile, review, "write_mode_conflict", "inferred_write_mode",
                profile.overrides.writeMode, "semantics.ai.write_mode",
                "影响写入模式相关风险检查和根因排序");
        addStringConflict(profile, review, "sync_mode_conflict", "inferred_sync_mode",
                profile.overrides.syncMode, "semantics.ai.sync_mode",
                "影响增量边界、CDC 和 full refresh 策略");
    }

    private void addStringConflict(TableProfile profile, ProfileReview review, String field,
                                   String metadataKey, String explicitValue, String configPath, String impact) {
        Object inferred = profile.metadata.get(metadataKey);
        if (!isBlank(explicitValue) && inferred != null
                && !explicitValue.equalsIgnoreCase(String.valueOf(inferred))) {
            addConfirmation(review,
                    field,
                    explicitValue,
                    0.9,
                    List.of("explicit_config:" + explicitValue, "inferred:" + inferred),
                    List.of("confirm authoritative " + configPath),
                    impact,
                    "确认 task.yaml 中 " + configPath + " 是否为权威配置");
        }
    }

    private void addConfirmation(ProfileReview review, String field, String suggestedValue, double confidence,
                                 List<String> evidence, List<String> missing, String impact, String nextAction) {
        ProfileReview.ConfirmationItem item = new ProfileReview.ConfirmationItem();
        item.field = field;
        item.suggestedValue = suggestedValue;
        item.confidence = confidence;
        item.evidence.addAll(evidence);
        item.missingInformation.addAll(missing);
        item.impact = impact;
        item.nextAction = nextAction;
        review.confirmationItems.add(item);
        review.impact.add(impact);
    }

    private List<String> listMetadata(TableProfile profile, String key) {
        Object value = profile.metadata.get(key);
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        return List.of();
    }

    private boolean missingEndpoint(TableProfile.EndpointProfile endpoint) {
        return endpoint == null
                || endpoint.type == null
                || endpoint.type.isBlank()
                || ((endpoint.table == null || endpoint.table.isBlank())
                && (endpoint.query == null || endpoint.query.isBlank()));
    }

    private boolean hasTimestampField(TableProfile profile) {
        return profile.columns.stream().anyMatch(column -> lower(column.type).contains("timestamp")
                || lower(column.name).contains("time"));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
