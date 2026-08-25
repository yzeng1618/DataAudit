// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

import java.util.List;
import java.util.ArrayList;

public class ExecutionPlan {
    public ScaleClass scaleClass = ScaleClass.SMALL;
    public String signalStrategy;
    public ProofMode proofMode = ProofMode.EXACT_DIFF;
    public String localizationStrategy;
    public List<String> decisionTrace = new ArrayList<>();
    public BoundaryRef boundary;
    public String reason;
    public String refuseReason;
}
