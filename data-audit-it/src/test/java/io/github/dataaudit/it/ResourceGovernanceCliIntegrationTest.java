// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.DataAuditMain;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGovernanceCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        loadSqliteDriver();
    }

    @Test
    void shouldRunBoundedKeyedDiffWithinLocalSqliteFixtureThreshold() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-resource-governed");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        seedOrders(sourceDb, false);
        seedOrders(targetDb, true);

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, resourceGovernedYaml(sourceDb, targetDb, reportsDir), StandardCharsets.UTF_8);

        long startedAt = System.nanoTime();
        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals(1, checkExit);
        assertTrue(elapsedMillis < 15_000L, "local SQLite bounded diff fixture exceeded 15s: " + elapsedMillis + "ms");

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("EXACT_DIFF", report.path("result").path("proof_mode").asText());
        assertEquals("EXACT", report.path("result").path("confidence").asText());
        assertTrue(report.path("result").path("diff").path("resource_bounded").asBoolean());
        assertFalse(report.path("result").path("diff").path("limit_exceeded").asBoolean());
        assertTrue(report.path("result").path("diff").path("samples").size() <= 2);
        assertTrue(report.path("evidence").path("progress_events").findValuesAsText("stage").contains("exact_diff"));
    }

    private String resourceGovernedYaml(Path sourceDb, Path targetDb, Path reportsDir) {
        return ""
                + "task:\n"
                + "  name: sqlite_resource_governed_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + sourceDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + targetDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "  columns:\n"
                + "    - order_id\n"
                + "    - status\n"
                + "    - amount\n"
                + "    - dt\n"
                + "  estimated_rows: 1000000\n"
                + "normalize:\n"
                + "  decimal_scale:\n"
                + "    amount: 2\n"
                + "resources:\n"
                + "  max_in_memory_rows: 32\n"
                + "  max_diff_samples: 2\n"
                + "  global_timeout_millis: 30000\n"
                + "  query_timeout_millis: 30000\n"
                + "  segment_parallelism: 1\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n";
    }

    private void createOrdersTable(Path dbPath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
        }
    }

    private void seedOrders(Path dbPath, boolean includeMismatch) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement statement = connection.prepareStatement(
                     "insert into orders(order_id, status, amount, dt) values (?, ?, ?, ?)")) {
            for (int orderId = 1; orderId <= 128; orderId++) {
                statement.setInt(1, orderId);
                statement.setString(2, orderId == 77 && includeMismatch ? "refunded" : "paid");
                statement.setString(3, orderId == 77 && includeMismatch ? "99.99" : "10.00");
                statement.setString(4, "2026-03-10");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void loadSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver is not available on the test classpath", e);
        }
    }
}
