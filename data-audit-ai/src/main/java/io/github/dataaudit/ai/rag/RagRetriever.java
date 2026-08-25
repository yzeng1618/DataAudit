// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import io.github.dataaudit.ai.model.RootCauseAnalysis;

import java.util.List;
import java.util.Map;

public interface RagRetriever {
    List<HistoricalCase> retrieve(Map<String, Object> features, int limit);

    List<RootCauseAnalysis.RetrievedCase> retrieveSummaries(Map<String, Object> features, int limit);
}
