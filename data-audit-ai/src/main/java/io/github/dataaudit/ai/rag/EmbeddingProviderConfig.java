// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import java.net.URI;

public class EmbeddingProviderConfig {
    public String provider = "local-hashing";
    public URI endpoint;
    public String apiKey;
    public String model;
    public int dimensions = 128;
    public boolean fallbackOnProviderError = true;
}
