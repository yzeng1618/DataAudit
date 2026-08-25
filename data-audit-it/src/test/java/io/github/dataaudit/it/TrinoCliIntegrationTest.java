// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.DataAuditMain;
import io.github.dataaudit.connector.trino.TrinoConnectorFactory;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class TrinoCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Container
    static GenericContainer<?> trino = new GenericContainer<>("trinodb/trino:471")
            .withExposedPorts(8080)
            // /v1/info answers 200 while the server is still initializing; the log
            // banner is the only signal that queries will be accepted.
            .waitingFor(Wait.forLogMessage(".*======== SERVER STARTED ========.*\\s", 1)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    // The banner races worker-node registration, and the node announcement can
    // still flap right after the first successful scan ("nodes is empty" from
    // FixedSourcePartitionedScheduler seconds later). Require several
    // consecutive successful distributed scans before letting tests run.
    @BeforeAll
    static void waitUntilQueryable() throws Exception {
        String url = "jdbc:trino://" + trino.getHost() + ":" + trino.getMappedPort(8080);
        Properties props = new Properties();
        props.setProperty("user", "test");
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        SQLException last = null;
        int consecutive = 0;
        while (System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(url, props);
                 Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery("select count(*) from tpch.tiny.nation")) {
                consecutive++;
                if (consecutive >= 3) {
                    return;
                }
                Thread.sleep(1000L);
            } catch (SQLException e) {
                last = e;
                consecutive = 0;
                Thread.sleep(500L);
            }
        }
        throw new IllegalStateException("Trino did not become stably queryable in time", last);
    }

    @Test
    void shouldUseTrinoTableModeForSmallExactDiff() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-trino-table");
        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, ""
                + "task:\n"
                + "  name: trino_table_exact_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "query_connector:\n"
                + "  type: trino\n"
                + "  uri: jdbc:trino://" + trino.getHost() + ":" + trino.getMappedPort(8080) + "\n"
                + "  user: test\n"
                + "source:\n"
                + "  type: trino\n"
                + "  catalog: tpch\n"
                + "  schema: tiny\n"
                + "  table: orders\n"
                + "target:\n"
                + "  type: trino\n"
                + "  catalog: tpch\n"
                + "  schema: tiny\n"
                + "  table: orders\n"
                + "object:\n"
                + "  key:\n"
                + "    - orderkey\n"
                + "  columns:\n"
                + "    - orderkey\n"
                + "    - orderstatus\n"
                + "  estimated_rows: 15000\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n", StandardCharsets.UTF_8);

        CommandLine cli = DataAuditMain.createCommandLine();
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
    }

    @Test
    void shouldUseTrinoGroupedSignalForQueryMode() throws Exception {
        Path tempDir = Files.createTempDirectory("data-audit-trino-query");
        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Files.writeString(taskFile, ""
                + "task:\n"
                + "  name: trino_query_signal_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "query_connector:\n"
                + "  type: trino\n"
                + "  uri: jdbc:trino://" + trino.getHost() + ":" + trino.getMappedPort(8080) + "\n"
                + "  user: test\n"
                + "source:\n"
                + "  type: sql\n"
                + "  query: |\n"
                + "    select orderkey,\n"
                + "           orderstatus,\n"
                + "           case when mod(orderkey, 2) = 0 then '2026-03-10' else '2026-03-11' end as dt\n"
                + "    from tpch.tiny.orders\n"
                + "    where orderkey in (1, 2, 3, 4)\n"
                + "target:\n"
                + "  type: sql\n"
                + "  query: |\n"
                + "    select orderkey,\n"
                + "           case when orderkey = 2 then 'BROKEN' else orderstatus end as orderstatus,\n"
                + "           case when mod(orderkey, 2) = 0 then '2026-03-10' else '2026-03-11' end as dt\n"
                + "    from tpch.tiny.orders\n"
                + "    where orderkey in (1, 2, 3, 4)\n"
                + "object:\n"
                + "  key:\n"
                + "    - orderkey\n"
                + "  columns:\n"
                + "    - orderkey\n"
                + "    - orderstatus\n"
                + "    - dt\n"
                + "  partition_by:\n"
                + "    - dt\n"
                + "  estimated_rows: 1000000\n"
                + "output:\n"
                + "  dir: " + reportsDir.toString().replace("\\", "/") + "\n", StandardCharsets.UTF_8);

        int checkExit = DataAuditMain.createCommandLine().execute("check", "-f", taskFile.toString());
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("LARGE", report.path("plan").path("scale_class").asText());
        assertTrue(report.path("plan").path("signal_backend").isMissingNode());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("EXACT_DIFF", report.path("result").path("proof_mode").asText());
        assertEquals("EXACT", report.path("result").path("confidence").asText());
        assertEquals("dt=2026-03-10", report.path("result").path("suspect_slices").get(0).path("slice_key").asText());
    }

    @Test
    void shouldPushDownRoutingSignalsForTrinoQueryMode() throws Exception {
        TaskFileSpec spec = new TaskFileSpec();
        spec.boundary.type = "job_finish";
        spec.queryConnector = new TaskFileSpec.QueryConnectorSpec();
        spec.queryConnector.type = "trino";
        spec.queryConnector.uri = "jdbc:trino://" + trino.getHost() + ":" + trino.getMappedPort(8080);
        spec.queryConnector.user = "test";
        spec.source.type = "sql";
        spec.source.query = ""
                + "select orderkey,\n"
                + "       orderstatus,\n"
                + "       case when mod(orderkey, 2) = 0 then '2026-03-10' else '2026-03-11' end as dt\n"
                + "from tpch.tiny.orders\n"
                + "where orderkey in (1, 2, 3, 4)";
        spec.object.columns.add("orderkey");
        spec.object.columns.add("orderstatus");
        spec.object.columns.add("dt");
        spec.object.routingStrategy = "dt";
        spec.object.estimatedRows = 200_000_000L;

        try (ConnectorBundle bundle = new TrinoConnectorFactory().open(spec, spec.source)) {
            assertTrue(bundle.getCapabilityDescriptor().supportsRoutingSignalPushdown);
            assertNotNull(bundle.getRoutingSignalReader());

            ReadRequest request = new ReadRequest();
            request.columns.addAll(spec.object.columns);
            List<SliceSignal> signals = bundle.getRoutingSignalReader().readRoutingSignals(request);

            assertEquals(2, signals.size());
            assertEquals("routing", signals.get(0).sliceType);
            assertTrue(signals.get(0).sliceKey.startsWith("routing="));
        }
    }
}
