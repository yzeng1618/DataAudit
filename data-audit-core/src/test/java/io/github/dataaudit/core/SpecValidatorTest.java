package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecValidatorTest {
    @Test
    void shouldRejectNegativeEstimatedBytesAndInvalidScaleOverride() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "validator_it";
        spec.source.type = "jdbc";
        spec.source.url = "jdbc:stub:source";
        spec.source.table = "source";
        spec.target.type = "jdbc";
        spec.target.url = "jdbc:stub:target";
        spec.target.table = "target";
        spec.output.dir = "./reports/validator";
        spec.object.estimatedBytes = -1L;
        spec.planner.scaleOverride = "huge";

        List<String> issues = new SpecValidator().validate(spec);

        assertTrue(issues.contains("object.estimated_bytes must be non-negative"));
        assertTrue(issues.contains("planner.scale_override must be small, large or xlarge"));
    }

    @Test
    void shouldRejectDesignReservedNativeLakehouseConnectors() {
        for (String type : List.of("hudi", "delta", "paimon")) {
            TaskFileSpec spec = validJdbcSpec();
            spec.source.type = type;

            List<String> issues = new SpecValidator().validate(spec);

            assertTrue(issues.stream().anyMatch(issue -> issue.contains(type)
                    && issue.contains("design-reserved")
                    && issue.contains("JDBC, Trino, or Iceberg")), type);
        }
    }

    @Test
    void shouldStillAcceptSupportedJdbcTrinoAndIcebergConfigs() {
        assertTrue(new SpecValidator().validate(validJdbcSpec()).isEmpty());
        assertTrue(new SpecValidator().validate(validTrinoSpec()).isEmpty());
        assertTrue(new SpecValidator().validate(validIcebergSpec()).isEmpty());
    }

    @Test
    void shouldUseDesignReservedMessageForTargetEndpointToo() {
        TaskFileSpec spec = validJdbcSpec();
        spec.target.type = "delta";

        List<String> issues = new SpecValidator().validate(spec);

        assertFalse(issues.contains("target.type must be sql, trino, jdbc or iceberg"));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("Delta native support is design-reserved")));
    }

    private TaskFileSpec validJdbcSpec() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "validator_it";
        spec.source.type = "jdbc";
        spec.source.url = "jdbc:stub:source";
        spec.source.table = "source";
        spec.target.type = "jdbc";
        spec.target.url = "jdbc:stub:target";
        spec.target.table = "target";
        spec.output.dir = "./reports/validator";
        return spec;
    }

    private TaskFileSpec validTrinoSpec() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "validator_trino";
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "trino";
        spec.queryConnector.uri = "jdbc:trino://localhost:8080";
        spec.queryConnector.user = "trino";
        spec.source.type = "trino";
        spec.source.catalog = "hive";
        spec.source.schema = "dw";
        spec.source.table = "orders_source";
        spec.target.type = "trino";
        spec.target.catalog = "hive";
        spec.target.schema = "dw";
        spec.target.table = "orders_target";
        spec.output.dir = "./reports/validator";
        return spec;
    }

    private TaskFileSpec validIcebergSpec() {
        TaskFileSpec spec = validJdbcSpec();
        spec.task.name = "validator_iceberg";
        spec.source.type = "iceberg";
        spec.source.table = "warehouse.orders_source";
        spec.source.url = null;
        spec.target.type = "iceberg";
        spec.target.table = "warehouse.orders_target";
        spec.target.url = null;
        return spec;
    }
}
