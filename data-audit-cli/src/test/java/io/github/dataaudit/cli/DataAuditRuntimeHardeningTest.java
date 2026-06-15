package io.github.dataaudit.cli;

import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuditRuntimeHardeningTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldExpandSupportedEnvironmentPlaceholdersBeforeValidation() throws Exception {
        Path task = writeTask("""
                source:
                  type: jdbc
                  url: ${SRC_URL}
                  username: source_user
                  password: ${SRC_PASSWORD}
                  table: source_orders
                target:
                  type: jdbc
                  url: ${TGT_URL}
                  username: target_user
                  password: ${TGT_PASSWORD}
                  table: target_orders
                query_connector:
                  type: trino
                  uri: ${TRINO_URI}
                  user: trino_user
                  password: ${TRINO_PASSWORD}
                """);

        Map<String, String> env = Map.of(
                "SRC_URL", "jdbc:sqlite:source.db",
                "SRC_PASSWORD", "source-secret",
                "TGT_URL", "jdbc:sqlite:target.db",
                "TGT_PASSWORD", "target-secret",
                "TRINO_URI", "http://trino.example:8080",
                "TRINO_PASSWORD", "trino-secret");

        TaskFileSpec spec = DataAuditMain.loadSpec(task, env::get);

        assertEquals("jdbc:sqlite:source.db", spec.source.url);
        assertEquals("source-secret", spec.source.password);
        assertEquals("jdbc:sqlite:target.db", spec.target.url);
        assertEquals("target-secret", spec.target.password);
        assertEquals("http://trino.example:8080", spec.queryConnector.uri);
        assertEquals("trino-secret", spec.queryConnector.password);
    }

    @Test
    void shouldReturnConfigErrorForMissingEnvironmentPlaceholder() throws Exception {
        Path task = writeTask("""
                source:
                  type: jdbc
                  url: jdbc:sqlite:source.db
                  username: source_user
                  password: ${MISSING_SRC_PASSWORD}
                  table: source_orders
                target:
                  type: jdbc
                  url: jdbc:sqlite:target.db
                  username: target_user
                  password: target-secret
                  table: target_orders
                """);

        CommandResult result = execute("plan", "-f", task.toString());

        assertEquals(2, result.exitCode);
        assertTrue(result.combinedOutput().contains("Missing environment variable MISSING_SRC_PASSWORD"));
        assertFalse(result.combinedOutput().contains("target-secret"));
    }

    @Test
    void shouldReturnConfigErrorForDesignReservedNativeConnector() throws Exception {
        Path task = writeTask("""
                source:
                  type: hudi
                  table: ods.orders
                target:
                  type: jdbc
                  url: jdbc:sqlite:target.db
                  table: target_orders
                """);

        CommandResult result = execute("plan", "-f", task.toString());

        assertEquals(2, result.exitCode);
        assertTrue(result.combinedOutput().contains("Hudi native support is design-reserved"));
        assertTrue(result.combinedOutput().contains("JDBC, Trino, or Iceberg"));
    }

    @Test
    void shouldNotPrintExpandedSecretValuesInCommandOutput() throws Exception {
        String existingSecret = System.getenv("PATH");
        assertTrue(existingSecret != null && !existingSecret.isBlank(), "PATH env var is required for this test");
        Path task = writeTask("""
                source:
                  type: jdbc
                  url: jdbc:sqlite:%s
                  username: source_user
                  password: ${PATH}
                  table: source_orders
                target:
                  type: jdbc
                  url: jdbc:sqlite:%s
                  username: target_user
                  password: ${PATH}
                  table: target_orders
                """.formatted(
                tempDir.resolve("source.db").toString().replace("\\", "\\\\"),
                tempDir.resolve("target.db").toString().replace("\\", "\\\\")));

        CommandResult result = execute("plan", "-f", task.toString());

        assertEquals(0, result.exitCode);
        assertFalse(result.combinedOutput().contains(existingSecret));
    }

    @Test
    void shouldPrintBuildMetadataWithUnknownFallbacks() throws Exception {
        CommandResult result = execute("version");

        assertEquals(0, result.exitCode);
        assertTrue(result.stdout.contains("version=unknown"));
        assertTrue(result.stdout.contains("build_time=unknown"));
        assertTrue(result.stdout.contains("commit_id=unknown"));
        assertTrue(result.stdout.contains("java_version="));
    }

    private Path writeTask(String endpoints) throws Exception {
        Path path = tempDir.resolve("task.yaml");
        Files.writeString(path, """
                task:
                  name: runtime_hardening
                  mode: post_check
                boundary:
                  type: job_finish
                  reference: latest
                %s
                object:
                  key:
                    - order_id
                  columns:
                    - order_id
                    - amount
                output:
                  dir: %s
                """.formatted(endpoints, tempDir.resolve("reports").toString().replace("\\", "\\\\")));
        return path;
    }

    private CommandResult execute(String... args) {
        return capture(() -> new CommandLine(new DataAuditMain()).execute(args));
    }

    private CommandResult capture(Supplier<Integer> action) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = action.get();
            return new CommandResult(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
        String combinedOutput() {
            return stdout + stderr;
        }
    }
}
