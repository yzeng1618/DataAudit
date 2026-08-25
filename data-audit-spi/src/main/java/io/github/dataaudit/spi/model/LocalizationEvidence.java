// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class LocalizationEvidence {
    public String strategy;
    public ProofMode proofMode;
    public ConfidenceLevel confidence;
    public boolean noKeyMode;
    public String fallbackReason;
    public List<SliceDescriptor> suspiciousScopes = new ArrayList<>();
}
