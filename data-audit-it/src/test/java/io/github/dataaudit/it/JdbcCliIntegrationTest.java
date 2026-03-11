package io.github.dataaudit.it;

import io.github.dataaudit.cli.DataAuditMain;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCliIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void shouldPlanAndCheckConsistentJdbcTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists source_orders(order_id bigint primary key, status varchar(20), amount numeric(10,2), dt varchar(10))");
            statement.execute("create table if not exists target_orders(order_id bigint primary key, status varchar(20), amount numeric(10,2), dt varchar(10))");
            statement.execute("insert into source_orders(order_id, status, amount, dt) values (1, 'paid', 10.00, '2026-03-10')");
            statement.execute("insert into source_orders(order_id, status, amount, dt) values (2, 'new', 20.00, '2026-03-10')");
            statement.execute("insert into target_orders(order_id, status, amount, dt) values (1, 'paid', 10.00, '2026-03-10')");
            statement.execute("insert into target_orders(order_id, status, amount, dt) values (2, 'new', 20.00, '2026-03-10')");
        }

        Path tempDir = Files.createTempDirectory("recon-it");
        Path taskFile = tempDir.resolve("task.yaml");
        Path reportsDir = tempDir.resolve("reports");
        Path stateFile = tempDir.resolve("state.db");
        String yaml = ""
                + "task:\n"
                + "  name: jdbc_it\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: " + postgres.getJdbcUrl() + "\n"
                + "  username: " + postgres.getUsername() + "\n"
                + "  password: " + postgres.getPassword() + "\n"
                + "  table: source_orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: " + postgres.getJdbcUrl() + "\n"
                + "  username: " + postgres.getUsername() + "\n"
                + "  password: " + postgres.getPassword() + "\n"
                + "  table: target_orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
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

        int planExit = new CommandLine(new DataAuditMain()).execute("plan", "-f", taskFile.toString());
        int checkExit = new CommandLine(new DataAuditMain()).execute("check", "-f", taskFile.toString());

        assertEquals(0, planExit);
        assertEquals(0, checkExit);
        assertTrue(Files.exists(reportsDir.resolve("report.json")));
        assertTrue(Files.exists(reportsDir.resolve("report.html")));
    }
}
