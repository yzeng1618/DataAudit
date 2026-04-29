package io.github.dataaudit.ai.model;

import java.util.ArrayList;
import java.util.List;

public class CopilotAnswer {
    public String answerVersion = "alpha-1";
    public String question;
    public String answer;
    public List<String> deterministicFacts = new ArrayList<>();
    public List<String> aiHypotheses = new ArrayList<>();
    public List<String> recommendedChecks = new ArrayList<>();
    public String safetyNotice = "The answer is grounded in DataAudit deterministic facts and AI hypotheses; AI does not replace audit status.";
}
