package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SummaryMetrics;

public class ProfileEvidenceEnricher {
    public void merge(TableProfile profile,
                      String side,
                      SchemaModel schema,
                      MetadataSnapshot metadata,
                      SummaryMetrics summary) {
        if (schema != null && schema.columns != null && !schema.columns.isEmpty()) {
            mergeSchema(profile, schema);
            addEvidence(profile, side + "_schema");
        }
        if (metadata != null) {
            if (metadata.schema != null && metadata.schema.columns != null && !metadata.schema.columns.isEmpty()) {
                mergeSchema(profile, metadata.schema);
            }
            if (metadata.attributes != null && !metadata.attributes.isEmpty()) {
                profile.metadata.put(side + "_metadata_attributes", metadata.attributes);
            }
            if (metadata.boundary != null) {
                profile.metadata.put(side + "_boundary", metadata.boundary);
            }
            if (metadata.sliceHints != null && !metadata.sliceHints.isEmpty()) {
                profile.metadata.put(side + "_slice_hints", metadata.sliceHints);
            }
            addEvidence(profile, side + "_metadata");
        }
        if (summary != null) {
            profile.statistics.distinctCount.putAll(summary.distinctCount);
            profile.statistics.nullCount.putAll(summary.nullCount);
            profile.statistics.minValues.putAll(summary.minValues);
            profile.statistics.maxValues.putAll(summary.maxValues);
            if (profile.statistics.estimatedRows == null && summary.rowCount > 0) {
                profile.statistics.estimatedRows = summary.rowCount;
            }
            profile.metadata.put(side + "_checksum", summary.checksum);
            addEvidence(profile, side + "_signal_summary");
        }
    }

    private void mergeSchema(TableProfile profile, SchemaModel schema) {
        for (SchemaModel.Column sourceColumn : schema.columns) {
            TableProfile.ColumnProfile target = profile.columns.stream()
                    .filter(column -> sourceColumn.name != null && sourceColumn.name.equalsIgnoreCase(column.name))
                    .findFirst()
                    .orElseGet(() -> {
                        TableProfile.ColumnProfile column = new TableProfile.ColumnProfile();
                        column.name = sourceColumn.name;
                        profile.columns.add(column);
                        return column;
                    });
            target.type = sourceColumn.type;
            target.nullable = sourceColumn.nullable;
            target.evidence.add("connector_schema");
        }
    }

    private void addEvidence(TableProfile profile, String evidence) {
        if (!profile.evidence.contains(evidence)) {
            profile.evidence.add(evidence);
        }
    }
}
