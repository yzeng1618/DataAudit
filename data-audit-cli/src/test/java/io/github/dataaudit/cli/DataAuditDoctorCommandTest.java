// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuditDoctorCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void aggregatesOfflineRuntimeChecksAsJson() throws Exception {
        Result result = execute("doctor", "--output-dir", tempDir.toString(), "--format", "json");

        assertEquals(0, result.exitCode);
        JsonNode json = new ObjectMapper().readTree(result.stdout);
        assertEquals("ok", json.path("status").asText());
        assertTrue(json.path("checks").size() >= 4);
    }

    @Test
    void reportsExplicitConnectionProbeFailureWithDedicatedExitCode() throws Exception {
        Path task = tempDir.resolve("task.yaml");
        Files.writeString(task, """
                task:
                  name: connection_failure
                source:
                  type: jdbc
                  url: jdbc:sqlite:%s
                  table: missing_source
                target:
                  type: jdbc
                  url: jdbc:sqlite:%s
                  table: missing_target
                object:
                  key: [id]
                  columns: [id]
                output:
                  dir: %s
                """.formatted(
                tempDir.resolve("source.db").toString().replace("\\", "\\\\"),
                tempDir.resolve("target.db").toString().replace("\\", "\\\\"),
                tempDir.resolve("reports").toString().replace("\\", "\\\\")),
                StandardCharsets.UTF_8);

        Result result = execute("doctor", "-f", task.toString());

        assertEquals(4, result.exitCode, "connection probing is the default, not an opt-in");
        assertTrue(result.output().contains("connection"));

        Result offline = execute("doctor", "-f", task.toString(), "--offline");
        assertEquals(0, offline.exitCode, "--offline skips the probe and keeps the static checks");
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
}
