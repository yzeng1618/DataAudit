// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.qa;

import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.CopilotAnswer;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotQaServiceTest {
    @Test
    void shouldAnswerAcceptanceQuestionFromDeterministicFacts() {
        RootCauseAnalysis analysis = new RootCauseAnalysis();
        analysis.recommendedChecks.add("检查目标端 snapshot");

        CopilotAnswer answer = new CopilotQaService().answer(
                new AuditPlan(),
                Map.of("status", "DIFF_FOUND", "proof_mode", "EXACT_DIFF", "diff_partition", "dt=2026-04-24"),
                analysis,
                "Does this block acceptance?");

        assertTrue(answer.answer.contains("不建议验收通过"));
        assertTrue(answer.deterministicFacts.stream().anyMatch(fact -> fact.contains("DIFF_FOUND")));
        assertTrue(answer.recommendedChecks.contains("检查目标端 snapshot"));
    }
}
