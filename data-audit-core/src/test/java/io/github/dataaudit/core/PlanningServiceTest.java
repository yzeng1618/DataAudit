package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanningServiceTest {
    @Test
    void shouldPlanGlobalChecksumForSmallJdbcTable() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.source.type = "jdbc";
        spec.target.type = "jdbc";
        spec.object.estimatedRows = 100L;

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals(ScaleClass.SMALL, plan.scaleClass);
        assertEquals("global_row_count_plus_checksum", plan.signalStrategy);
        assertEquals("none", plan.localizationStrategy);
        assertEquals(ProofMode.GLOBAL_CHECKSUM, plan.proofMode);
        assertThrows(NoSuchFieldException.class, () -> ExecutionPlan.class.getDeclaredField("signalBackend"));
    }

    @Test
    void shouldPickMetadataFirstForIceberg() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "snapshot";
        spec.source.type = "jdbc";
        spec.target.type = "iceberg";
        spec.object.estimatedRows = 200_000_000L;

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        target.supportsSnapshotBoundary = true;
        target.supportsMetadataStats = true;
        target.supportsRoutingSignalPushdown = true;

        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "snapshot";
        boundary.reference = "1";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals(ScaleClass.XLARGE, plan.scaleClass);
        assertEquals("partition_stats_or_metadata", plan.signalStrategy);
        assertEquals("routing_digest", plan.localizationStrategy);
        assertEquals(ProofMode.ROUTING_DIGEST, plan.proofMode);
    }

    @Test
    void shouldPickTrinoGroupedSignalForLargeSqlObject() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "trino";
        spec.queryConnector.uri = "jdbc:trino://localhost:8080";
        spec.queryConnector.user = "test";
        spec.source.type = "sql";
        spec.source.table = "orders_src";
        spec.target.type = "sql";
        spec.target.table = "orders_tgt";
        spec.object.estimatedRows = 1_000_000L;
        spec.object.partitionBy.add("dt");

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals(ScaleClass.LARGE, plan.scaleClass);
        assertEquals("global_row_count_plus_grouped_checksum", plan.signalStrategy);
        assertEquals("partition_window", plan.localizationStrategy);
        assertEquals(ProofMode.GROUPED_CHECKSUM, plan.proofMode);
    }

    @Test
    void shouldUseVirtualBucketForLargeJdbcWithoutNaturalSlice() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.source.type = "jdbc";
        spec.target.type = "jdbc";
        spec.object.estimatedRows = 5_000_000L;
        spec.object.key.add("order_id");

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals(ScaleClass.LARGE, plan.scaleClass);
        assertEquals("key_hash_bucket", plan.localizationStrategy);
        assertEquals(ProofMode.GROUPED_CHECKSUM, plan.proofMode);
    }

    @Test
    void shouldTreatTrinoAliasAsTrinoQueryPlane() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "trino";
        spec.queryConnector.uri = "jdbc:trino://localhost:8080";
        spec.queryConnector.user = "test";
        spec.source.type = "trino";
        spec.source.table = "orders_src";
        spec.target.type = "trino";
        spec.target.table = "orders_tgt";
        spec.object.estimatedRows = 1_000_000L;
        spec.object.partitionBy.add("dt");

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals(ScaleClass.LARGE, plan.scaleClass);
        assertEquals("global_row_count_plus_grouped_checksum", plan.signalStrategy);
        assertEquals("partition_window", plan.localizationStrategy);
        assertEquals(ProofMode.GROUPED_CHECKSUM, plan.proofMode);
    }
}
