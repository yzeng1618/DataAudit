// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public class TaskFileProfileBuilder implements ProfileBuilder {
    private final ProfileCollectionOptions options;

    public TaskFileProfileBuilder() {
        this(new ProfileCollectionOptions());
    }

    public TaskFileProfileBuilder(ProfileCollectionOptions options) {
        this.options = options;
    }

    @Override
    public TableProfile build(TaskFileSpec spec) {
        TableProfile profile = new TableProfile();
        copyEndpoint(spec.source, profile.source);
        copyEndpoint(spec.target, profile.target);
        profile.boundary.type = spec.boundary.type;
        profile.boundary.reference = spec.boundary.reference;
        profile.boundary.gracePeriod = spec.boundary.gracePeriod;
        profile.statistics.estimatedRows = spec.object.estimatedRows;
        profile.statistics.estimatedBytes = spec.object.estimatedBytes;
        profile.statistics.maxSampleRows = options.maxSampleRows;
        profile.statistics.maxSampleFields = options.maxSampleFields;
        profile.statistics.timeoutMillis = options.timeoutMillis;

        profile.overrides.primaryKeys.addAll(spec.object.key);
        profile.overrides.partitionFields.addAll(spec.object.partitionBy);
        profile.overrides.decimalScale.putAll(spec.normalize.decimalScale);
        profile.overrides.timezone = spec.normalize.timezone;
        profile.overrides.writeMode = spec.semantics.ai.writeMode;
        profile.overrides.syncMode = spec.semantics.ai.syncMode;

        profile.syncContext.timezone = spec.normalize.timezone;
        profile.syncContext.writeMode = firstNonBlank(spec.semantics.ai.writeMode, null);
        profile.syncContext.syncMode = firstNonBlank(spec.semantics.ai.syncMode, spec.task.mode);

        int fieldCount = 0;
        for (String columnName : spec.object.columns) {
            TableProfile.ColumnProfile column = new TableProfile.ColumnProfile();
            column.name = columnName;
            column.type = inferType(columnName, spec);
            column.evidence.add("task.object.columns");
            profile.columns.add(column);
            if (fieldCount++ < options.maxSampleFields) {
                profile.samples.add(maskedSample(columnName));
            }
        }
        if (profile.columns.isEmpty()) {
            profile.missingInformation.add("object_columns_not_configured");
        }
        profile.evidence.add("task_yaml");
        profile.missingInformation.add("connector_schema_metadata_not_collected");
        profile.missingInformation.add("live_stats_sample_collection_not_executed");
        if (spec.queryConnector != null) {
            profile.retrievalHints.add("query_connector:" + spec.queryConnector.type);
        }
        profile.retrievalHints.add(nonNull(profile.source.type) + "_to_" + nonNull(profile.target.type));
        return profile;
    }

    private void copyEndpoint(TaskFileSpec.EndpointSpec input, TableProfile.EndpointProfile output) {
        output.type = input.type;
        output.catalog = input.catalog;
        output.schema = input.schema;
        output.table = input.table;
        output.query = input.query;
        output.options.putAll(input.options);
    }

    private String inferType(String columnName, TaskFileSpec spec) {
        Integer scale = spec.normalize.decimalScale.get(columnName);
        if (scale != null) {
            return "decimal(38," + scale + ")";
        }
        String lower = columnName.toLowerCase(Locale.ROOT);
        if (lower.contains("amount") || lower.contains("price") || lower.contains("cost")
                || lower.contains("fee") || lower.contains("balance")) {
            return "decimal(20,2)";
        }
        if (lower.endsWith("_time") || lower.contains("timestamp")) {
            return "timestamp";
        }
        if ("dt".equals(lower) || lower.endsWith("_date") || lower.contains("date")) {
            return "date";
        }
        if (lower.contains("json") || lower.contains("payload") || lower.contains("extra")
                || lower.contains("properties")) {
            return "json";
        }
        if (lower.endsWith("_id") || "id".equals(lower)) {
            return "varchar";
        }
        return "varchar";
    }

    private TableProfile.SampleProfile maskedSample(String columnName) {
        TableProfile.SampleProfile sample = new TableProfile.SampleProfile();
        sample.field = columnName;
        sample.masked = true;
        sample.pattern = "masked:" + semanticPattern(columnName);
        sample.hash = sha256(columnName + ":masked");
        sample.summary = "masked sample placeholder; raw values are not persisted";
        return sample;
    }

    private String semanticPattern(String columnName) {
        String lower = columnName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_id") || "id".equals(lower)) {
            return "identifier";
        }
        if (lower.contains("time") || "dt".equals(lower)) {
            return "temporal";
        }
        if (lower.contains("amount") || lower.contains("price")) {
            return "numeric";
        }
        return "text";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash masked sample", e);
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String nonNull(String value) {
        return value == null ? "unknown" : value;
    }
}
