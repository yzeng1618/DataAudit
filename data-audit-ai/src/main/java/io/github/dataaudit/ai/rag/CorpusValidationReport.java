// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import java.util.ArrayList;
import java.util.List;

public class CorpusValidationReport {
    private final List<String> acceptedCaseIds = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();

    public boolean valid() {
        return issues.isEmpty();
    }

    public List<String> acceptedCaseIds() {
        return List.copyOf(acceptedCaseIds);
    }

    public List<String> issues() {
        return List.copyOf(issues);
    }

    void accept(String caseId) {
        acceptedCaseIds.add(caseId);
    }

    void reject(String issue) {
        issues.add(issue);
    }
}
