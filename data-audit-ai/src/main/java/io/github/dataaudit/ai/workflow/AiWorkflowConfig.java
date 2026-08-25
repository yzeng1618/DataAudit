// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.workflow;

import java.net.URI;

public class AiWorkflowConfig {
    public String provider = "disabled";
    public URI endpoint;
    public String apiKey;
    public String model = "mimo-v2.5-pro";
    public boolean fallbackOnProviderError = true;

    public AiWorkflowConfig() {
    }

    public AiWorkflowConfig(String provider, URI endpoint, String apiKey) {
        this.provider = provider == null || provider.isBlank() ? "disabled" : provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    public AiWorkflowConfig(String provider, URI endpoint, String apiKey, String model) {
        this(provider, endpoint, apiKey);
        if (model != null && !model.isBlank()) {
            this.model = model;
        }
    }

    public boolean providerEnabled() {
        return provider != null && !"disabled".equalsIgnoreCase(provider);
    }
}
