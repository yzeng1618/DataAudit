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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDialectCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldValidateHiveDialectSegmentPathOnSqliteBackend() throws Exception {
        Path tempDir = Files.createTempDirectory("recon-hive-sqlite");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);

        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(sourceDb, 3, "paid", "30.00", "2026-03-11");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
        insert(targetDb, 3, "paid", "30.00", "2026-03-11");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Path stateFile = tempDir.resolve("state.db");
        String yaml = ""
                + "task:\n"
                + "  name: hive_sqlite_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + sourceDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: hive\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + targetDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: hive\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "planner:\n"
                + "  mode: segment_first\n"
                + "  hints:\n"
                + "    estimated_rows: 1000000\n"
                + "    max_exact_rows: 100\n"
                + "    partition_keys:\n"
                + "      - dt\n"
                + "compare:\n"
                + "  segment:\n"
                + "    by:\n"
                + "      - dt\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n"
                + "state:\n"
                + "  backend: sqlite\n"
                + "  path: " + stateFile.toString().replace("\\", "/") + "\n";
        Files.write(taskFile, yaml.getBytes(StandardCharsets.UTF_8));

        CommandLine cli = new CommandLine(new DataAuditMain());
        int planExit = cli.execute("plan", "-f", taskFile.toString());
        int checkExit = cli.execute("check", "-f", taskFile.toString());

        assertEquals(0, planExit);
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("partitioned_big_table", report.path("plan").path("object_class").asText());
        assertEquals("schema -> summary -> segment -> diff", report.path("plan").path("selected_path").asText());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("dt=2026-03-10", report.path("result").path("suspect_segments").get(0).path("segment_key").asText());
    }

    @Test
    void shouldValidateDorisDialectExactDiffOnSqliteBackend() throws Exception {
        Path tempDir = Files.createTempDirectory("recon-doris-sqlite");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);

        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Path stateFile = tempDir.resolve("state.db");
        String yaml = ""
                + "task:\n"
                + "  name: doris_sqlite_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + sourceDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: doris\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + targetDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: doris\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "planner:\n"
                + "  mode: auto\n"
                + "  hints:\n"
                + "    estimated_rows: 2\n"
                + "    max_exact_rows: 100\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n"
                + "state:\n"
                + "  backend: sqlite\n"
                + "  path: " + stateFile.toString().replace("\\", "/") + "\n";
        Files.write(taskFile, yaml.getBytes(StandardCharsets.UTF_8));

        CommandLine cli = new CommandLine(new DataAuditMain());
        int planExit = cli.execute("plan", "-f", taskFile.toString());
        int checkExit = cli.execute("check", "-f", taskFile.toString());

        assertEquals(0, planExit);
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("small_table_once", report.path("plan").path("object_class").asText());
        assertEquals("schema -> exact diff", report.path("plan").path("selected_path").asText());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("checksum_mismatch", report.path("result").path("root_cause").asText());
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
}
