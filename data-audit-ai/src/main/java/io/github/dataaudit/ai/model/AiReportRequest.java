// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class AiReportRequest {
    public AuditPlan plan;
    public Map<String, Object> result = new LinkedHashMap<>();
    public RootCauseAnalysis analysis;
    public String template = "technical";
    public Path outputPath;
}
