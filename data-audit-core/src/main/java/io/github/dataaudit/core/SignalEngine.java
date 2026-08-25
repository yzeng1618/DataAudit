// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.GlobalSignalEvidence;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class SignalEngine {
    public GlobalSignalEvidence evaluate(TaskFileSpec spec,
                                         ScaleClass scaleClass,
                                         SignalReader source,
                                         SignalReader target) throws Exception {
        ReadRequest request = ReadRequestFactory.baseRequest(spec);
        SummaryMetrics sourceSummary = source.readSummary(request);
        SummaryMetrics targetSummary = target.readSummary(request);
        GlobalSignalEvidence evidence = new GlobalSignalEvidence();
        evidence.sourceSummary = sourceSummary;
        evidence.targetSummary = targetSummary;
        return evidence;
    }
}
