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
        spec.planner.hints.estimatedRows = 100L;
        spec.planner.hints.maxExactRows = 1_000L;

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
        assertEquals("boundary metadata -> schema -> summary -> segment -> diff", plan.selectedPath);
    }

    @Test
    void shouldRespectSegmentFirstMode() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.source.type = "jdbc";
        spec.target.type = "jdbc";
        spec.planner.mode = "segment_first";
        spec.planner.hints.estimatedRows = 100L;
        spec.planner.hints.maxExactRows = 1_000L;
        spec.planner.hints.partitionKeys.add("dt");

        CapabilityDescriptor source = new CapabilityDescriptor();
        CapabilityDescriptor target = new CapabilityDescriptor();
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.stable = true;

        ExecutionPlan plan = new PlanningService().plan(spec, source, target, boundary);
        assertEquals("partitioned_big_table", plan.objectClass);
        assertEquals("schema -> summary -> segment -> diff", plan.selectedPath);
    }

    @Test
    void shouldRespectExactFirstMode() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "demo";
        spec.boundary.type = "job_finish";
        spec.source.type = "jdbc";
        spec.target.type = "jdbc";
        spec.planner.mode = "exact_first";
        spec.planner.hints.estimatedRows = 5_000_000L;

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
}
