// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.stream.Stream;

public class ConnectorRegistry {
    private final List<ConnectorFactory> factories;

    public ConnectorRegistry(List<ConnectorFactory> factories) {
        this.factories = factories;
    }

    public static ConnectorRegistry load() {
        return load(System.getenv("DATAAUDIT_PLUGINS_DIR"));
    }

    /**
     * Loads the built-in factories from the application class path, then any
     * third-party factories packaged as jars in {@code pluginsDir}. Built-in
     * factories are consulted first, so a plugin cannot shadow a supported
     * endpoint type; it can only add new ones.
     */
    public static ConnectorRegistry load(String pluginsDir) {
        List<ConnectorFactory> factories = new ArrayList<>();
        ServiceLoader.load(ConnectorFactory.class).forEach(factories::add);
        factories.addAll(loadPluginFactories(pluginsDir));
        return new ConnectorRegistry(factories);
    }

    private static List<ConnectorFactory> loadPluginFactories(String pluginsDir) {
        if (pluginsDir == null || pluginsDir.isBlank()) {
            return List.of();
        }
        Path dir = Paths.get(pluginsDir.trim());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            URL[] jars = entries
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .map(ConnectorRegistry::toUrl)
                    .toArray(URL[]::new);
            if (jars.length == 0) {
                return List.of();
            }
            URLClassLoader pluginLoader = new URLClassLoader(jars, ConnectorFactory.class.getClassLoader());
            List<ConnectorFactory> plugins = new ArrayList<>();
            for (ConnectorFactory factory : ServiceLoader.load(ConnectorFactory.class, pluginLoader)) {
                // The parent-delegating loader re-discovers built-ins; keep only
                // factories whose classes actually came from a plugin jar.
                if (factory.getClass().getClassLoader() == pluginLoader) {
                    plugins.add(factory);
                }
            }
            return plugins;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load connector plugins from " + dir, e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid connector plugin path: " + path, e);
        }
    }

    public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) throws Exception {
        for (ConnectorFactory factory : factories) {
            if (factory.supports(endpointSpec)) {
                return factory.open(spec, endpointSpec);
            }
        }
        throw new IllegalArgumentException("Unsupported endpoint type: " + endpointSpec.type);
    }

    public List<String> types() {
        List<String> types = factories.stream()
                .map(ConnectorFactory::type)
                .filter(type -> type != null && !type.isBlank())
                .distinct()
                .sorted()
                .toList();
        return Collections.unmodifiableList(types);
    }
}
