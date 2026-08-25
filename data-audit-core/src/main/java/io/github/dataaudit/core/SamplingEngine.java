// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.LocalizationEvidence;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class SamplingEngine {
    public LocalizationEvidence proportionalSample(TaskFileSpec spec,
                                                   RowStreamReader sourceRowReader,
                                                   RowStreamReader targetRowReader) {
        LocalizationEvidence evidence = new LocalizationEvidence();
        evidence.strategy = "proportional_sampling";
        evidence.proofMode = ProofMode.SAMPLING;
        evidence.confidence = ConfidenceLevel.LOW;
        evidence.noKeyMode = true;
        evidence.fallbackReason = "xlarge_sampling_fallback";
        SliceDescriptor descriptor = new SliceDescriptor();
        descriptor.sliceKey = "full_table";
        descriptor.sliceType = "sampling";
        descriptor.drilldownable = false;
        descriptor.reason = "proportional_sampling_scope";
        if (spec != null && spec.object != null) {
            descriptor.rowEstimate = spec.object.estimatedRows;
        }
        evidence.suspiciousScopes.add(descriptor);
        return evidence;
    }
}
