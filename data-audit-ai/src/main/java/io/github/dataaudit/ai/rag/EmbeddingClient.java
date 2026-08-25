// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

public interface EmbeddingClient {
    double[] embed(String text);

    String name();
}
