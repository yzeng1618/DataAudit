// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.TaskFileSpec;

/**
 * Entry point for a connector implementation, discovered through
 * {@link java.util.ServiceLoader}. An implementation must be listed in
 * {@code META-INF/services/io.github.dataaudit.spi.connector.ConnectorFactory}
 * to be visible to the engine's connector registry.
 *
 * <p>Factories are stateless: all per-run resources belong to the
 * {@link ConnectorBundle} returned by {@link #open}.
 */
public interface ConnectorFactory {

    /**
     * Canonical endpoint type this factory serves (for example {@code "jdbc"},
     * {@code "trino"}, {@code "iceberg"}). Used for diagnostics and doctor
     * output; matching is done by {@link #supports}, which may accept aliases.
     */
    String type();

    /**
     * Returns {@code true} when this factory can open the given endpoint.
     * Called with every configured endpoint; implementations must tolerate a
     * {@code null} spec or unknown {@code type} value and simply return
     * {@code false}.
     */
    boolean supports(TaskFileSpec.EndpointSpec endpointSpec);

    /**
     * Opens a live bundle of readers for one endpoint of one run.
     *
     * @param spec         the full task file, for cross-cutting settings such as
     *                     {@code query_connector}
     * @param endpointSpec the endpoint to open; guaranteed to have passed
     *                     {@link #supports}
     * @return a bundle whose readers match the advertised
     *         {@link io.github.dataaudit.spi.model.CapabilityDescriptor}; the
     *         caller closes it when the run finishes
     * @throws Exception if the endpoint configuration is invalid or the target
     *                   system cannot be reached; the engine surfaces this as an
     *                   execution failure rather than a data difference
     */
    ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) throws Exception;
}
