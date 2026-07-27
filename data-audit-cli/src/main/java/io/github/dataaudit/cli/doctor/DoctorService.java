package io.github.dataaudit.cli.doctor;

import io.github.dataaudit.cli.config.ConfigCheckResult;
import io.github.dataaudit.cli.config.TaskConfigService;
import io.github.dataaudit.core.ConnectorRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

public class DoctorService {
    private final ConnectorRegistry connectorRegistry;
    private final TaskConfigService configService;

    public DoctorService(ConnectorRegistry connectorRegistry, TaskConfigService configService) {
        this.connectorRegistry = connectorRegistry;
        this.configService = configService;
    }

    public ConfigCheckResult diagnose(Path taskFile, Path outputDir, boolean testConnection) {
        ConfigCheckResult result = new ConfigCheckResult();
        int javaFeature = Runtime.version().feature();
        result.addCheck("java", javaFeature >= 17 ? "ok" : "error",
                "Java feature version: " + javaFeature + " (required: 17+)");

        var types = connectorRegistry.types();
        result.addCheck("connectors", types.isEmpty() ? "error" : "ok",
                types.isEmpty() ? "No connectors discovered" : "Discovered connectors: " + String.join(", ", types));

        try {
            Class.forName("org.sqlite.JDBC");
            result.addCheck("sqlite", "ok", "SQLite JDBC driver is available");
        } catch (Exception e) {
            result.addCheck("sqlite", "error", "SQLite JDBC driver is unavailable");
        }

        checkWritable(result, outputDir);

        if (taskFile != null) {
            merge(result, configService.validate(taskFile, testConnection));
        }
        return result;
    }

    private void checkWritable(ConfigCheckResult result, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            Path probe = Files.createTempFile(outputDir, ".data-audit-doctor-", ".tmp");
            Files.deleteIfExists(probe);
            result.addCheck("output", "ok", "Output directory is writable: " + outputDir);
        } catch (Exception e) {
            result.addCheck("output", "error", "Output directory is not writable: " + outputDir);
        }
    }

    private void merge(ConfigCheckResult target, ConfigCheckResult source) {
        for (ConfigCheckResult.Check check : source.checks) {
            target.addCheck(check.name, check.status, check.message);
        }
    }
}
