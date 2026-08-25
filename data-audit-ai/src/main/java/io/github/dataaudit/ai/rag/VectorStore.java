// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;

import java.util.List;

public interface VectorStore {
    void index(List<HistoricalCase> cases);

    List<VectorSearchResult> search(double[] query, int limit);

    String name();
}
