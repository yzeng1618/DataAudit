// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class CopilotAnswer {
    public String artifactVersion = "1";
    public String artifactType = "answer";
    public String producer = "data-audit-ai";
    public String schemaVersion = "data-audit-copilot-answer-v1";
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public String answerVersion = "alpha-1";
    public String question;
    public String answer;
    public List<String> deterministicFacts = new ArrayList<>();
    public List<String> aiHypotheses = new ArrayList<>();
    public List<String> recommendedChecks = new ArrayList<>();
    public String safetyNotice = "The answer is grounded in DataAudit deterministic facts and AI hypotheses; AI does not replace audit status.";
}
