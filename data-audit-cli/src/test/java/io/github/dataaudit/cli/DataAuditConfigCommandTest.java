package io.github.dataaudit.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.config.TaskConfigService;
import io.github.dataaudit.core.ConnectorRegistry;
import io.github.dataaudit.core.SpecValidator;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuditConfigCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void initializesAndValidatesConfiguration() throws Exception {
        Path task = tempDir.resolve("task.yaml");

        assertEquals(0, execute("config", "init", "-o", task.toString()).exitCode);
        assertTrue(Files.exists(task));
        assertEquals(2, execute("config", "init", "-o", task.toString()).exitCode);
        assertEquals(0, execute("config", "init", "-o", task.toString(), "--force").exitCode);

        Result validation = execute("config", "validate", "-f", task.toString(), "--format", "json");
        assertEquals(0, validation.exitCode);
        JsonNode json = new ObjectMapper().readTree(validation.stdout);
        assertEquals("ok", json.path("status").asText());
        assertTrue(json.path("checks").isArray());
        assertTrue(json.path("errors").isArray());
    }

    @Test
    void rejectsInvalidConfiguration() throws Exception {
        Path task = tempDir.resolve("invalid.yaml");
        Files.writeString(task, "task: {}\n", StandardCharsets.UTF_8);

        Result validation = execute("config", "validate", "-f", task.toString());

        assertEquals(2, validation.exitCode);
        assertTrue(validation.output().contains("task.name is required"));
    }

    @Test
    void offlineValidationDoesNotOpenConnectors() {
        CountingRegistry registry = new CountingRegistry();
        TaskConfigService service = new TaskConfigService(registry, new SpecValidator(), ignored -> null);
        Path task = tempDir.resolve("task.yaml");
        assertTrue(service.initialize(task, false).isOk());

        assertTrue(service.validate(task, false).isOk());
        assertEquals(0, registry.openCount);
    }

    private Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = new CommandLine(new DataAuditMain()).execute(args);
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }

    private static class CountingRegistry extends ConnectorRegistry {
        private int openCount;

        private CountingRegistry() {
            super(List.of());
        }

        @Override
        public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
            openCount++;
            throw new AssertionError("offline validation must not open connectors");
        }
    }
}
