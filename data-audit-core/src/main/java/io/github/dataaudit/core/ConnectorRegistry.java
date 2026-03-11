package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class ConnectorRegistry {
    private final List<ConnectorFactory> factories;

    public ConnectorRegistry(List<ConnectorFactory> factories) {
        this.factories = factories;
    }

    public static ConnectorRegistry load() {
        List<ConnectorFactory> factories = new ArrayList<>();
        ServiceLoader.load(ConnectorFactory.class).forEach(factories::add);
        return new ConnectorRegistry(factories);
    }

    public ConnectorBundle open(TaskFileSpec.EndpointSpec endpointSpec) throws Exception {
        for (ConnectorFactory factory : factories) {
            if (factory.supports(endpointSpec)) {
                return factory.open(endpointSpec);
            }
        }
        throw new IllegalArgumentException("Unsupported endpoint type: " + endpointSpec.type);
    }
}

