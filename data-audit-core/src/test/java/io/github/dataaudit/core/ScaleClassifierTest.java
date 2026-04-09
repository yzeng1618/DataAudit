package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScaleClassifierTest {
    @Test
    void shouldDefaultToLargeWhenEstimatesAreMissing() {
        TaskFileSpec spec = new TaskFileSpec();
        assertEquals(ScaleClass.LARGE, new ScaleClassifier().classify(spec));
    }

    @Test
    void shouldClassifySmallWhenRowsAndBytesAreWithinThreshold() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.estimatedRows = 100_000L;
        spec.object.estimatedBytes = 100L * 1024 * 1024;

        assertEquals(ScaleClass.SMALL, new ScaleClassifier().classify(spec));
    }
}
