package io.github.dataaudit.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.cli.DataAuditMain;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class TrinoCliIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Container
    static GenericContainer<?> trino = new GenericContainer<>("trinodb/trino:471")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/v1/info").forStatusCode(200));

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

        CommandLine cli = new CommandLine(new DataAuditMain());
        assertEquals(0, cli.execute("plan", "-f", taskFile.toString()));
        assertEquals(0, cli.execute("check", "-f", taskFile.toString()));

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("small_table_once", report.path("plan").path("object_class").asText());
        assertEquals("schema -> exact diff", report.path("plan").path("selected_path").asText());
        assertEquals("CONSISTENT", report.path("result").path("status").asText());
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

        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());
        assertEquals(1, checkExit);

        JsonNode report = objectMapper.readTree(reportsDir.resolve("report.json").toFile());
        assertEquals("gate -> signal -> localization -> drilldown", report.path("plan").path("selected_path").asText());
        assertEquals("trino_grouped_signal", report.path("plan").path("signal_backend").asText());
        assertEquals("DIFF_FOUND", report.path("result").path("status").asText());
        assertEquals("dt=2026-03-10", report.path("result").path("suspect_slices").get(0).path("slice_key").asText());
    }
}
