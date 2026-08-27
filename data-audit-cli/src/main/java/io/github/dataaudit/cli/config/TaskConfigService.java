// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.dataaudit.core.ConnectorRegistry;
import io.github.dataaudit.core.SpecValidator;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TaskConfigService {
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|pwd|token|api[_-]?key)(\\s*[=:]\\s*)[^\\s,;]+");

    private final ConnectorRegistry connectorRegistry;
    private final SpecValidator validator;
    private final Function<String, String> envLookup;
    private final ObjectMapper mapper;

    public TaskConfigService(ConnectorRegistry connectorRegistry,
                             SpecValidator validator,
                             Function<String, String> envLookup) {
        this.connectorRegistry = connectorRegistry;
        this.validator = validator;
        this.envLookup = envLookup;
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public ConfigCheckResult initialize(Path output, boolean force) {
        ConfigCheckResult result = new ConfigCheckResult();
        try {
            if (Files.exists(output) && !force) {
                throw new FileAlreadyExistsException(output.toString());
            }
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream template = getClass().getResourceAsStream("/templates/task-basic.yaml")) {
                if (template == null) {
                    throw new IllegalStateException("Bundled task template is missing");
                }
                Files.copy(template, output, StandardCopyOption.REPLACE_EXISTING);
            }
            result.addCheck("config_init", "ok", "Created task configuration: " + output);
        } catch (FileAlreadyExistsException e) {
            result.addCheck("config_init", "error", "Configuration already exists; use --force to overwrite: " + output);
        } catch (Exception e) {
            result.addCheck("config_init", "error", "Unable to create configuration: " + safeMessage(e));
        }
        return result;
    }

    public ConfigCheckResult validate(Path taskFile, boolean testConnection) {
        ConfigCheckResult result = new ConfigCheckResult();
        TaskFileSpec spec;
        try {
            spec = load(taskFile);
            List<String> issues = validator.validate(spec);
            if (!issues.isEmpty()) {
                for (String issue : issues) {
                    result.addCheck("configuration", "error", issue);
                }
                return result;
            }
            result.addCheck("configuration", "ok", "Task configuration syntax is valid");
        } catch (Exception e) {
            result.addCheck("configuration", "error", safeMessage(e));
            return result;
        }

        if (testConnection) {
            probeEndpoint(result, spec, "source", spec.source);
            probeEndpoint(result, spec, "target", spec.target);
        }
        return result;
    }

    public TaskFileSpec load(Path taskFile) throws Exception {
        try (InputStream inputStream = Files.newInputStream(taskFile)) {
            Object raw = new Yaml().load(inputStream);
            if (!(raw instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Task configuration must be a YAML object");
            }
            TaskFileSpec spec = mapper.convertValue(raw, TaskFileSpec.class);
            expandEnvironment(spec);
            return spec;
        }
    }

    private void probeEndpoint(ConfigCheckResult result,
                               TaskFileSpec spec,
                               String label,
                               TaskFileSpec.EndpointSpec endpoint) {
        try (ConnectorBundle bundle = connectorRegistry.open(spec, endpoint)) {
            if (bundle.getSchemaReader() == null) {
                throw new IllegalStateException("connector does not expose a schema reader");
            }
            bundle.getSchemaReader().readSchema();
            result.addCheck(label + "_connection", "ok", label + " connection probe succeeded");
        } catch (Exception e) {
            Throwable rootCause = e;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            result.addCheck(label + "_connection", "error",
                    label + " connection probe failed: " + safeMessage(rootCause));
        }
    }

    private void expandEnvironment(TaskFileSpec spec) {
        if (spec.queryConnector != null) {
            spec.queryConnector.uri = expand(spec.queryConnector.uri, "query_connector.uri");
            spec.queryConnector.user = expand(spec.queryConnector.user, "query_connector.user");
            spec.queryConnector.password = expand(spec.queryConnector.password, "query_connector.password");
        }
        expandEndpoint(spec.source, "source");
        expandEndpoint(spec.target, "target");
    }

    private void expandEndpoint(TaskFileSpec.EndpointSpec endpoint, String label) {
        if (endpoint == null) {
            return;
        }
        endpoint.url = expand(endpoint.url, label + ".url");
        endpoint.username = expand(endpoint.username, label + ".username");
        endpoint.password = expand(endpoint.password, label + ".password");
        endpoint.uri = expand(endpoint.uri, label + ".uri");
        endpoint.warehouse = expand(endpoint.warehouse, label + ".warehouse");
        endpoint.location = expand(endpoint.location, label + ".location");
    }

    private String expand(String value, String fieldPath) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = envLookup.apply(name);
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "Missing environment variable " + name + " referenced by " + fieldPath);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return SECRET_ASSIGNMENT.matcher(message).replaceAll("$1$2***");
    }
}
