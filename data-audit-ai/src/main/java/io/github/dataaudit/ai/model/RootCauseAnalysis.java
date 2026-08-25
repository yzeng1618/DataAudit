// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class RootCauseAnalysis {
    public String artifactVersion = "1";
    public String artifactType = "root_cause_analysis";
    public String producer = "data-audit-ai";
    public String schemaVersion = "data-audit-root-cause-analysis-v1";
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public String analysisVersion = "alpha-1";
    public String anomalySummary;
    public List<PossibleCause> possibleRootCauses = new ArrayList<>();
    public List<RetrievedCase> retrievedCases = new ArrayList<>();
    public List<String> recommendedChecks = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();
    public String aiSafetyNotice = "该分析是概率性 AI 分析，只能提供可能原因和排查建议，不能替代 DataAudit 的确定性核验结果。";

    public static class PossibleCause {
        public String hypothesis;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public List<RetrievedCase> retrievedCases = new ArrayList<>();
        public List<String> recommendedChecks = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
    }

    public static class RetrievedCase {
        public String id;
        public String title;
        public double score;
        public List<String> matchedEvidence = new ArrayList<>();
    }
}
