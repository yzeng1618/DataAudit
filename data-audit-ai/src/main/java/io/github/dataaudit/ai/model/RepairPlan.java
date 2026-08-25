// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class RepairPlan {
    public String artifactVersion = "1";
    public String artifactType = "repair_plan";
    public String producer = "data-audit-ai";
    public String schemaVersion = "data-audit-repair-plan-v1";
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public String repairVersion = "alpha-1";
    public String deterministicStatus;
    public List<RepairAction> actions = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();
    public String safetyNotice = "Repair plans are suggestions. DataAudit AI never mutates source or target data automatically.";

    public static class RepairAction {
        public String id;
        public String type;
        public String description;
        public String riskLevel;
        public boolean autoExecutable;
        public boolean requiresApproval = true;
        public String configPath;
        public String suggestedValue;
        public String command;
        public List<String> evidence = new ArrayList<>();
        public List<String> nextChecks = new ArrayList<>();
    }
}
