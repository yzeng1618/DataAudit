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

class SqliteDialectCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        loadSqliteDriver();
    }

    @Test
    void shouldRunJdbcFallbackExactDiffWithNewSchema() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-jdbc-small");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, jdbcYaml("jdbc_small_it", sourceDb, targetDb, reportsDir, 1L, null), StandardCharsets.UTF_8);

        CommandLine cli = new CommandLine(new DataAuditMain());
        assertEquals(0, cli.execute("plan", "-f", taskFile.toString()));
        assertEquals(0, cli.execute("check", "-f", taskFile.toString()));

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("SMALL", report.path("plan").path("scale_class").asText());
        assertEquals("global_row_count_plus_checksum", report.path("plan").path("signal_strategy").asText());
        assertEquals("CONSISTENT", report.path("result").path("status").asText());
        assertEquals("GLOBAL_CHECKSUM", report.path("result").path("proof_mode").asText());
        assertEquals("HIGH", report.path("result").path("confidence").asText());
        assertEquals(true, report.path("plan").path("signal_backend").isMissingNode());
        assertEquals(true, report.path("plan").path("object_class").isMissingNode());
        assertEquals(true, report.path("plan").path("selected_path").isMissingNode());
        assertEquals(true, report.path("result").path("schema_issues").isMissingNode());
        assertEquals(true, report.path("result").path("dml_audit").isMissingNode());
        assertEquals(true, report.path("result").path("ddl_audit").isMissingNode());
    }

    @Test
    void shouldRunMysqlDialectFallbackExactDiff() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-jdbc-mysql-small");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, jdbcYaml("jdbc_mysql_small_it", sourceDb, targetDb, reportsDir, 1L, null, "mysql"), StandardCharsets.UTF_8);

        CommandLine cli = new CommandLine(new DataAuditMain());
        assertEquals(0, cli.execute("plan", "-f", taskFile.toString()));
        assertEquals(0, cli.execute("check", "-f", taskFile.toString()));

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("SMALL", report.path("plan").path("scale_class").asText());
        assertEquals("CONSISTENT", report.path("result").path("status").asText());
        assertEquals("GLOBAL_CHECKSUM", report.path("result").path("proof_mode").asText());
    }

    @Test
    void shouldFindSuspectSlicesForPartitionedJdbcFallback() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-jdbc-slices");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(sourceDb);
        createOrdersTable(targetDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-11");
        insert(targetDb, 1, "paid", "99.99", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-11");

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, jdbcYaml("jdbc_slice_it", sourceDb, targetDb, reportsDir, 1_000_000L, "dt"), StandardCharsets.UTF_8);

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("LARGE", report.path("plan").path("scale_class").asText());
        assertEquals("partition_window", report.path("plan").path("localization_strategy").asText());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("EXACT_DIFF", report.path("result").path("proof_mode").asText());
        assertEquals("EXACT", report.path("result").path("confidence").asText());
        assertEquals("dt=2026-03-10", report.path("result").path("suspect_slices").get(0).path("slice_key").asText());
    }

    @Test
    void shouldApplyRenameMappingAndNormalizationFromSemantics() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-jdbc-rename");
        Path sourceDb = tempDir.resolve("source.db");
        Path targetDb = tempDir.resolve("target.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourceDb);
             Statement statement = connection.createStatement()) {
            statement.execute("create table source_orders(order_id integer primary key, status text, old_amount decimal(10,2), dt text)");
            statement.execute("insert into source_orders(order_id, status, old_amount, dt) values (1, 'PAID', 10.0, '2026-03-10')");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + targetDb);
             Statement statement = connection.createStatement()) {
            statement.execute("create table target_orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
            statement.execute("insert into target_orders(order_id, status, amount, dt) values (1, 'paid', 10.00, '2026-03-10')");
        }

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        String yaml = ""
                + "task:\n"
                + "  name: jdbc_rename_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + sourceDb.toString().replace("\\", "/") + "\n"
                + "  table: source_orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + targetDb.toString().replace("\\", "/") + "\n"
                + "  table: target_orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "  columns:\n"
                + "    - order_id\n"
                + "    - status\n"
                + "    - old_amount\n"
                + "    - dt\n"
                + "  estimated_rows: 1\n"
                + "normalize:\n"
                + "  case_insensitive_columns:\n"
                + "    - status\n"
                + "  decimal_scale:\n"
                + "    amount: 2\n"
                + "semantics:\n"
                + "  ddl:\n"
                + "    rename_mapping:\n"
                + "      old_amount: amount\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n";
        Files.writeString(taskFile, yaml, StandardCharsets.UTF_8);

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(0, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("CONSISTENT", report.path("result").path("status").asText());
        assertEquals("GLOBAL_CHECKSUM", report.path("result").path("proof_mode").asText());
    }

    private String jdbcYaml(String taskName,
                            Path sourceDb,
                            Path targetDb,
                            Path reportsDir,
                            long estimatedRows,
                            String partitionBy) {
        return jdbcYaml(taskName, sourceDb, targetDb, reportsDir, estimatedRows, partitionBy, "postgres");
    }

    private String jdbcYaml(String taskName,
                            Path sourceDb,
                            Path targetDb,
                            Path reportsDir,
                            long estimatedRows,
                            String partitionBy,
                            String dialect) {
        StringBuilder builder = new StringBuilder();
        builder.append("task:\n")
                .append("  name: ").append(taskName).append('\n')
                .append("boundary:\n")
                .append("  type: job_finish\n")
                .append("source:\n")
                .append("  type: jdbc\n")
                .append("  url: jdbc:sqlite:").append(sourceDb.toString().replace("\\", "/")).append('\n')
                .append("  table: orders\n")
                .append("  options:\n")
                .append("    dialect: ").append(dialect).append('\n')
                .append("target:\n")
                .append("  type: jdbc\n")
                .append("  url: jdbc:sqlite:").append(targetDb.toString().replace("\\", "/")).append('\n')
                .append("  table: orders\n")
                .append("  options:\n")
                .append("    dialect: ").append(dialect).append('\n')
                .append("object:\n")
                .append("  key:\n")
                .append("    - order_id\n")
                .append("  columns:\n")
                .append("    - order_id\n")
                .append("    - status\n")
                .append("    - amount\n")
                .append("    - dt\n")
                .append("  estimated_rows: ").append(estimatedRows).append('\n');
        if (partitionBy != null) {
            builder.append("  partition_by:\n")
                    .append("    - ").append(partitionBy).append('\n');
        }
        builder.append("normalize:\n")
                .append("  decimal_scale:\n")
                .append("    amount: 2\n")
                .append("output:\n")
                .append("  dir: ").append(reportsDir.toString().replace("\\", "/")).append('\n');
        return builder.toString();
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
