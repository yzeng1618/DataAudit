package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
