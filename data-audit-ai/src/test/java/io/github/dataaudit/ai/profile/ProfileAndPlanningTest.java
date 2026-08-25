// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.ProfileReview;
import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.ai.planner.AiStrategyPlanner;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileAndPlanningTest {
    @Test
    void shouldBuildProfileFromTaskAndMaskSamples() {
        TaskFileSpec spec = orderTask();
        spec.semantics.ai.writeMode = "overwrite";

        TableProfile profile = new TaskFileProfileBuilder().build(spec);

        assertEquals("oracle", profile.source.type);
        assertEquals("iceberg", profile.target.type);
        assertEquals(List.of("order_id"), profile.overrides.primaryKeys);
        assertEquals("overwrite", profile.syncContext.writeMode);
        assertTrue(profile.samples.stream().allMatch(sample -> sample.masked));
        assertTrue(profile.missingInformation.contains("connector_schema_metadata_not_collected"));
    }

    @Test
    void shouldPlanSemanticChecksAndRespectExplicitConfig() {
        TaskFileSpec spec = orderTask();
        spec.semantics.ai.writeMode = "overwrite";
        TableProfile profile = new TaskFileProfileBuilder().build(spec);

        AuditPlan plan = new AiStrategyPlanner().plan(profile, List.of("Iceberg overwrite partition coverage"));

        assertEquals("large_partitioned_table", plan.tableClassification.tableType);
        assertTrue(plan.semanticAnalysis.candidatePrimaryKeys.contains("order_id"));
        assertTrue(plan.semanticAnalysis.metricFields.contains("amount"));
        assertTrue(plan.semanticAnalysis.enumFields.contains("status"));
        assertTrue(plan.semanticAnalysis.timeFields.contains("create_time"));
        assertTrue(plan.semanticAnalysis.partitionFields.contains("dt"));
        assertTrue(plan.riskAnalysis.stream().anyMatch(risk -> "partition_overwrite_risk".equals(risk.riskType)));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "global_row_count".equals(step.type)));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "global_checksum".equals(step.type)));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "metric_sum".equals(step.type)));
        assertTrue(plan.recommendedSteps.stream().anyMatch(step -> "enum_distribution".equals(step.type)));
        assertFalse(plan.deterministicBoundary.aiConsistencyConclusion);
    }

    @Test
    void shouldGateOnlyHighImpactUncertainProfileFields() {
        TableProfile profile = new TableProfile();
        profile.source.type = "jdbc";
        profile.source.table = "public.orders";
        profile.target.type = "jdbc";
        profile.target.table = "ads.orders";
        profile.columns.add(column("order_id", "varchar"));
        profile.columns.add(column("amount", "decimal(20,2)"));

        ProfileReview review = new ProfileQualityGate().evaluate(profile);

        assertEquals(ProfileReview.Status.REVIEW_REQUIRED, review.status);
        assertTrue(review.confirmationItems.stream().anyMatch(item -> "candidate_key".equals(item.field)));
    }

    @Test
    void shouldMergeConnectorSchemaMetadataAndSignalEvidence() {
        TableProfile profile = new TaskFileProfileBuilder().build(orderTask());
        SchemaModel schema = new SchemaModel();
        SchemaModel.Column amount = new SchemaModel.Column();
        amount.name = "amount";
        amount.type = "decimal(20,2)";
        amount.nullable = false;
        schema.columns.add(amount);
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.attributes.put("snapshot_id", "12345");
        SummaryMetrics summary = new SummaryMetrics();
        summary.rowCount = 5000000L;
        summary.distinctCount.put("order_id", 5000000L);

        new ProfileEvidenceEnricher().merge(profile, "source", schema, metadata, summary);

        assertTrue(profile.evidence.contains("source_schema"));
        assertTrue(profile.evidence.contains("source_metadata"));
        assertTrue(profile.evidence.contains("source_signal_summary"));
        assertEquals("decimal(20,2)", profile.columns.stream()
                .filter(column -> "amount".equals(column.name))
                .findFirst()
                .orElseThrow()
                .type);
        assertEquals(5000000L, profile.statistics.distinctCount.get("order_id"));
    }

    @Test
    void shouldCollectSignalEvidenceAndMaskedBoundedSamples() {
        TaskFileSpec spec = orderTask();
        spec.source.type = "memory";
        spec.target.type = "memory";
        ProfileCollectionOptions options = new ProfileCollectionOptions();
        options.maxSampleRows = 1;
        options.maxSampleFields = 2;

        TableProfile profile = new ProfileCollector(options, new ProfileEvidenceEnricher(), new SampleMasker())
                .collect(spec, (task, endpoint) -> connectorBundle());

        assertTrue(profile.evidence.contains("source_signal_summary"));
        assertTrue(profile.statistics.distinctCount.containsKey("order_id"));
        assertFalse(profile.samples.isEmpty());
        assertTrue(profile.samples.stream().allMatch(sample -> sample.masked));
        assertTrue(profile.samples.size() <= 2);
        assertTrue(profile.samples.stream().noneMatch(sample ->
                String.valueOf(sample.pattern).contains("secret")
                        || String.valueOf(sample.hash).contains("secret")
                        || String.valueOf(sample.summary).contains("secret")));
    }

    @Test
    void shouldReviewMissingWriteMode() {
        TableProfile profile = baseReviewProfile();
        profile.syncContext.syncMode = "batch";

        ProfileReview review = new ProfileQualityGate().evaluate(profile);

        assertEquals(ProfileReview.Status.REVIEW_REQUIRED, review.status);
        assertTrue(review.confirmationItems.stream().anyMatch(item -> "write_mode".equals(item.field)));
    }

    @Test
    void shouldReviewExplicitAndInferredConflicts() {
        TableProfile profile = baseReviewProfile();
        profile.syncContext.writeMode = "overwrite";
        profile.syncContext.syncMode = "batch";
        profile.overrides.writeMode = "overwrite";
        profile.metadata.put("inferred_write_mode", "append");

        ProfileReview review = new ProfileQualityGate().evaluate(profile);

        assertEquals(ProfileReview.Status.REVIEW_REQUIRED, review.status);
        assertTrue(review.confirmationItems.stream().anyMatch(item -> "write_mode_conflict".equals(item.field)));
    }

    private TaskFileSpec orderTask() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "orders";
        spec.source.type = "oracle";
        spec.source.table = "ods.orders";
        spec.target.type = "iceberg";
        spec.target.table = "dw.orders";
        spec.object.key.add("order_id");
        spec.object.partitionBy.add("dt");
        spec.object.columns.addAll(List.of("order_id", "user_id", "amount", "status", "create_time", "dt"));
        spec.object.estimatedRows = 5_000_000L;
        spec.normalize.timezone = "Asia/Shanghai";
        return spec;
    }

    private TableProfile.ColumnProfile column(String name, String type) {
        TableProfile.ColumnProfile column = new TableProfile.ColumnProfile();
        column.name = name;
        column.type = type;
        return column;
    }

    private TableProfile baseReviewProfile() {
        TableProfile profile = new TableProfile();
        profile.source.type = "jdbc";
        profile.source.table = "public.orders";
        profile.target.type = "jdbc";
        profile.target.table = "ads.orders";
        profile.columns.add(column("amount", "decimal(20,2)"));
        return profile;
    }

    private ConnectorBundle connectorBundle() {
        SchemaModel schema = new SchemaModel();
        SchemaModel.Column orderId = new SchemaModel.Column();
        orderId.name = "order_id";
        orderId.type = "varchar";
        orderId.nullable = false;
        schema.columns.add(orderId);
        SummaryMetrics summary = new SummaryMetrics();
        summary.rowCount = 5_000_000L;
        summary.checksum = "abc";
        summary.distinctCount.put("order_id", 5_000_000L);
        summary.nullCount.put("amount", 0L);
        SignalReader signalReader = new SignalReader() {
            @Override
            public SummaryMetrics readSummary(ReadRequest request) {
                return summary;
            }

            @Override
            public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) {
                return List.of();
            }
        };
        return new ConnectorBundle(
                null,
                () -> schema,
                signalReader,
                (request, visitor) -> visitor.accept(Map.of(
                        "order_id", "secret-order-1",
                        "amount", "12.30",
                        "status", "PAID")),
                null,
                null);
    }
}
