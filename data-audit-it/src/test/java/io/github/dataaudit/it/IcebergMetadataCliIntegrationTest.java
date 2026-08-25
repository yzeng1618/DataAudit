// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.DataAuditMain;
import io.github.dataaudit.connector.iceberg.IcebergConnectorFactory;
import io.github.dataaudit.it.support.IcebergFixtureSupport;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("requires-posix-filesystem")
class IcebergMetadataCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        loadSqliteDriver();
    }

    @Test
    void shouldUseNativeIcebergMetadataForSnapshotBoundary() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-iceberg-consistent");
        Path sourceDb = tempDir.resolve("source.db");
        createOrdersTable(sourceDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");

        Path tableLocation = tempDir.resolve("warehouse").resolve("orders");
        IcebergFixtureSupport.resetOrdersTable(tableLocation, Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10")
        ));

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, jdbcToIcebergYaml(sourceDb, tableLocation, reportsDir), StandardCharsets.UTF_8);

        CommandLine cli = new CommandLine(new DataAuditMain());
        assertEquals(0, cli.execute("plan", "-f", taskFile.toString()));
        assertEquals(0, cli.execute("check", "-f", taskFile.toString()));

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("SMALL", report.path("plan").path("scale_class").asText());
        assertEquals("CONSISTENT", report.path("result").path("status").asText());
        assertEquals("GLOBAL_CHECKSUM", report.path("result").path("proof_mode").asText());
        assertEquals("HIGH", report.path("result").path("confidence").asText());
        assertTrue(report.path("plan").path("signal_backend").isMissingNode());
        assertTrue(report.path("plan").path("object_class").isMissingNode());
        assertTrue(report.path("plan").path("selected_path").isMissingNode());
        assertTrue(report.path("plan").path("decision_trace").isArray());
    }

    @Test
    void shouldExposeRoutingSignalReaderForIcebergEndpoint() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-iceberg-routing");
        Path tableLocation = tempDir.resolve("warehouse").resolve("orders");
        IcebergFixtureSupport.resetOrdersTable(tableLocation, Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10"),
                IcebergFixtureSupport.order(2, "paid", "30.00", "2026-03-11")
        ));

        TaskFileSpec spec = new TaskFileSpec();
        spec.boundary.type = "snapshot";
        spec.boundary.reference = "latest";
        spec.source.type = "iceberg";
        spec.source.location = tableLocation.toString().replace("\\", "/");
        spec.source.table = "orders";
        spec.object.partitionBy.add("dt");
        spec.object.columns.add("order_id");
        spec.object.columns.add("dt");
        spec.object.estimatedRows = 200_000_000L;

        try (ConnectorBundle bundle = new IcebergConnectorFactory().open(spec, spec.source)) {
            assertTrue(bundle.getCapabilityDescriptor().supportsRoutingSignalPushdown);
            assertNotNull(bundle.getRoutingSignalReader());
        }
    }

    @Test
    void shouldDetectPartitionedDiffAcrossIcebergAndJdbc() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-iceberg-diff");
        Path targetDb = tempDir.resolve("target.db");
        createOrdersTable(targetDb);
        insert(targetDb, 1, "paid", "99.99", "2026-03-10");
        insert(targetDb, 2, "paid", "30.00", "2026-03-11");

        Path tableLocation = tempDir.resolve("warehouse").resolve("orders");
        IcebergFixtureSupport.resetOrdersTable(tableLocation, Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10"),
                IcebergFixtureSupport.order(2, "paid", "30.00", "2026-03-11")
        ));

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, icebergToJdbcYaml(tableLocation, targetDb, reportsDir), StandardCharsets.UTF_8);

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("EXACT_DIFF", report.path("result").path("proof_mode").asText());
        assertEquals("EXACT", report.path("result").path("confidence").asText());
        assertEquals("dt=2026-03-10", report.path("result").path("suspect_slices").get(0).path("slice_key").asText());
    }

    private String jdbcToIcebergYaml(Path sourceDb, Path tableLocation, Path reportsDir) {
        return ""
                + "task:\n"
                + "  name: iceberg_metadata_it\n"
                + "boundary:\n"
                + "  type: snapshot\n"
                + "  reference: latest\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + sourceDb.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "target:\n"
                + "  type: iceberg\n"
                + "  location: " + tableLocation.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "  columns:\n"
                + "    - order_id\n"
                + "    - status\n"
                + "    - amount\n"
                + "    - dt\n"
                + "  estimated_rows: 1\n"
                + "normalize:\n"
                + "  decimal_scale:\n"
                + "    amount: 2\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n";
    }

    private String icebergToJdbcYaml(Path tableLocation, Path targetDb, Path reportsDir) {
        return ""
                + "task:\n"
                + "  name: iceberg_to_jdbc_it\n"
                + "boundary:\n"
                + "  type: snapshot\n"
                + "  reference: latest\n"
                + "source:\n"
                + "  type: iceberg\n"
                + "  location: " + tableLocation.toString().replace("\\", "/") + "\n"
                + "  table: orders\n"
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
                + "  partition_by:\n"
                + "    - dt\n"
                + "  estimated_rows: 1000000\n"
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
