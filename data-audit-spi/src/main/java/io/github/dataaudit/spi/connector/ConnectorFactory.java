package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.TaskFileSpec;

public interface ConnectorFactory {
    String type();

    boolean supports(TaskFileSpec.EndpointSpec endpointSpec);

    ConnectorBundle open(TaskFileSpec.EndpointSpec endpointSpec) throws Exception;
}

