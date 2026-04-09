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
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoKeyFallbackCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        loadSqliteDriver();
    }

    @Test
    void shouldWriteLargeNoKeyXorFallbackFields() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-large-nokey");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "99.99", "2026-03-10");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, noKeyYaml("large_nokey_it", sourceDb, targetDb, reportsDir, 5_000_000L), StandardCharsets.UTF_8);

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("diff_found", report.path("result").path("status").asText().toLowerCase(Locale.ROOT));
        assertEquals("xor_checksum_plus_sample", report.path("result").path("proof_mode").asText().toLowerCase(Locale.ROOT));
        assertEquals("medium", report.path("result").path("confidence").asText().toLowerCase(Locale.ROOT));
        assertTrue(report.path("result").path("no_key_mode").asBoolean());
    }

    @Test
    void shouldWriteXLargeSamplingFallbackFields() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-xlarge-nokey");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, noKeyYaml("xlarge_nokey_it", sourceDb, targetDb, reportsDir, 200_000_000L), StandardCharsets.UTF_8);

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(0, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("consistent", report.path("result").path("status").asText().toLowerCase(Locale.ROOT));
        assertEquals("sampling", report.path("result").path("proof_mode").asText().toLowerCase(Locale.ROOT));
        assertEquals("low", report.path("result").path("confidence").asText().toLowerCase(Locale.ROOT));
        assertTrue(report.path("result").path("no_key_mode").asBoolean());
        assertEquals("xlarge_sampling_fallback", report.path("result").path("fallback_reason").asText());
    }

    private String noKeyYaml(String taskName,
                             Path sourceDb,
                             Path targetDb,
                             Path reportsDir,
                             long estimatedRows) {
        return ""
                + "task:\n"
                + "  name: " + taskName + "\n"
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
                + "  columns:\n"
                + "    - order_id\n"
                + "    - status\n"
                + "    - amount\n"
                + "    - dt\n"
                + "  estimated_rows: " + estimatedRows + "\n"
                + "  estimated_bytes: 8589934592\n"
                + "normalize:\n"
                + "  decimal_scale:\n"
                + "    amount: 2\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n";
    }

    private void createOrdersTable(Path dbPath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
        }
    }

    private void insert(Path dbPath, int orderId, String status, String amount, String dt) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            statement.execute("insert into orders(order_id, status, amount, dt) values (" + orderId + ", '" + status + "', " + amount + ", '" + dt + "')");
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
