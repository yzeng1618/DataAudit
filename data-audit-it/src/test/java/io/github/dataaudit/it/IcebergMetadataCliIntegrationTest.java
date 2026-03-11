package io.github.dataaudit.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.DataAuditMain;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;
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

class IcebergMetadataCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPlanMetadataFirstAndReturnPartialCheckForIcebergTarget() throws Exception {
        Path tempDir = Files.createTempDirectory("recon-iceberg-it");
        Path sourceDb = tempDir.resolve("source.db");
        createOrdersTable(sourceDb);
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        Path tableLocation = tempDir.resolve("warehouse").resolve("orders");
        createIcebergTable(tableLocation);

        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Path stateFile = tempDir.resolve("state.db");
        String yaml = ""
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
                + "planner:\n"
                + "  mode: metadata_first\n"
                + "  hints:\n"
                + "    object_class: lakehouse_object\n"
                + "    prefer_metadata: true\n"
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
        assertEquals(4, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("lakehouse_object", report.path("plan").path("object_class").asText());
        assertEquals("boundary metadata -> schema -> summary -> segment -> diff", report.path("plan").path("selected_path").asText());
        assertEquals("PARTIAL", report.path("result").path("status").asText());
        assertEquals("data_reader_unavailable", report.path("result").path("root_cause").asText());
        assertTrue(report.path("result").path("suspect_segments").isArray());
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

    private void createIcebergTable(Path tableLocation) throws Exception {
        Files.createDirectories(tableLocation);
        Schema schema = new Schema(
                Types.NestedField.required(1, "order_id", Types.LongType.get()),
                Types.NestedField.optional(2, "status", Types.StringType.get()),
                Types.NestedField.optional(3, "dt", Types.StringType.get())
        );
        PartitionSpec spec = PartitionSpec.unpartitioned();
        HadoopTables tables = new HadoopTables(new Configuration());
        Table table = tables.create(schema, spec, tableLocation.toString());

        Path dataFilePath = tableLocation.resolve("data-file.parquet");
        Files.write(dataFilePath, new byte[]{0});
        DataFile dataFile = DataFiles.builder(spec)
                .withPath(dataFilePath.toString().replace("\\", "/"))
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(1)
                .withRecordCount(2)
                .build();
        table.newAppend().appendFile(dataFile).commit();
    }
}
