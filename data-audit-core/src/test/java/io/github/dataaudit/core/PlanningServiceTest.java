package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningServiceTest {
    @Test
    void shouldPickExactDiffForSmallJdbcTable() {
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
        assertEquals("small_table_once", plan.objectClass);
        assertEquals("schema -> exact diff", plan.selectedPath);
    }

    @Test
    void shouldPickMetadataFirstForIceberg() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "snapshot";
        spec.source.type = "jdbc";
        spec.target.type = "iceberg";

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        target.supportsSnapshotBoundary = true;
        target.supportsMetadataStats = true;

        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "snapshot";
        boundary.reference = "1";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals("lakehouse_object", plan.objectClass);
        assertEquals("boundary metadata -> schema -> signal -> localization -> drilldown", plan.selectedPath);
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
        assertEquals("partitioned_big_table", plan.objectClass);
        assertEquals("gate -> signal -> localization -> drilldown", plan.selectedPath);
        assertEquals("trino_grouped_signal", plan.signalBackend);
        assertEquals("natural_slice", plan.localizationStrategy);
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
        assertEquals("partitioned_big_table", plan.objectClass);
        assertEquals("gate -> signal -> localization -> drilldown", plan.selectedPath);
        assertEquals("virtual_bucket", plan.localizationStrategy);
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
        assertEquals("partitioned_big_table", plan.objectClass);
        assertEquals("gate -> signal -> localization -> drilldown", plan.selectedPath);
        assertEquals("trino_grouped_signal", plan.signalBackend);
        assertEquals("natural_slice", plan.localizationStrategy);
    }
}
