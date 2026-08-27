// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRunEndToEndAndShowTheDifferences() {
        Path demoDir = tempDir.resolve("demo");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            exitCode = DataAuditMain.createCommandLine().execute("demo", "--dir", demoDir.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, "finding the planted differences is the demo succeeding\n" + output);
        assertTrue(output.contains("DIFF_FOUND"), output);
        assertTrue(output.contains("order_id=3"), "raw evidence names the mismatched row\n" + output);
        assertTrue(output.contains("Next steps:"), output);
        assertTrue(Files.exists(demoDir.resolve("task.yaml")));
        assertTrue(Files.exists(demoDir.resolve("reports").resolve("report.json")));
        assertTrue(Files.exists(demoDir.resolve("reports").resolve("report.html")));
    }
}
