// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.GlobalSignalEvidence;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalEngineTest {
    @Test
    void shouldUseGlobalSignalForSmallTable() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.object.estimatedRows = 10L;

        SummaryMetrics left = new SummaryMetrics();
        left.rowCount = 10L;
        left.checksum = "10:1";
        SummaryMetrics right = new SummaryMetrics();
        right.rowCount = 10L;
        right.checksum = "10:1";

        GlobalSignalEvidence evidence = new SignalEngine().evaluate(
                spec,
                ScaleClass.SMALL,
                fixedSignalReader(left),
                fixedSignalReader(right)
        );

        assertEquals(10L, evidence.sourceSummary.rowCount);
        assertEquals(10L, evidence.targetSummary.rowCount);
    }

    private SignalReader fixedSignalReader(SummaryMetrics metrics) {
        return new SignalReader() {
            @Override
            public SummaryMetrics readSummary(ReadRequest request) {
                return metrics;
            }

            @Override
            public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) {
                return new ArrayList<>();
            }
        };
    }
}
