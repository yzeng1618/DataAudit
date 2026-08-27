// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.Callable;

/**
 * Self-contained first-run experience: generates two small SQLite databases
 * with three typical differences, writes a ready-to-run task file, and audits
 * them with raw evidence so the very first output already shows which row is
 * wrong and why. No external database, network, or build step required.
 */
@Command(name = "demo", mixinStandardHelpOptions = true,
        description = "Run a self-contained demo against generated SQLite data.")
public class DemoCommand implements Callable<Integer> {

    @Option(names = "--dir", description = "demo working directory (default: ./data-audit-demo)")
    private Path dir = Paths.get("data-audit-demo");

    @Override
    public Integer call() throws Exception {
        Path demoDir = dir.toAbsolutePath().normalize();
        Files.createDirectories(demoDir);
        Path sourceDb = demoDir.resolve("source.db");
        Path targetDb = demoDir.resolve("target.db");
        Files.deleteIfExists(sourceDb);
        Files.deleteIfExists(targetDb);
        seedSource(sourceDb);
        seedTarget(targetDb);
        Path taskFile = demoDir.resolve("task.yaml");
        Files.writeString(taskFile, taskYaml(sourceDb, targetDb, demoDir.resolve("reports")));

        System.out.println("[OK] Sample data created: source 4 rows / target 4 rows"
                + " (1 changed value, 1 row missing, 1 extra row)");
        System.out.println("[OK] Task file written: " + taskFile);
        System.out.println();

        int exitCode = DataAuditMain.createCommandLine().execute("check", "-f", taskFile.toString());

        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Open " + taskFile + " and point source/target at your own databases.");
        System.out.println("  2. Run: java -jar data-audit.jar check -f " + taskFile);
        // Finding the planted differences is the demo succeeding, so exit code 1
        // ("diff found") from the check still means the demo worked.
        return exitCode == 0 || exitCode == 1 ? 0 : exitCode;
    }

    private void seedSource(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table orders (order_id integer primary key, amount real, dt text)");
            statement.executeUpdate("insert into orders values (1, 10.0, '2026-03-10')");
            statement.executeUpdate("insert into orders values (2, 20.0, '2026-03-10')");
            statement.executeUpdate("insert into orders values (3, 30.0, '2026-03-11')");
            statement.executeUpdate("insert into orders values (4, 40.0, '2026-03-11')");
        }
    }

    private void seedTarget(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table orders (order_id integer primary key, amount real, dt text)");
            statement.executeUpdate("insert into orders values (1, 10.0, '2026-03-10')");
            statement.executeUpdate("insert into orders values (2, 20.0, '2026-03-10')");
            statement.executeUpdate("insert into orders values (3, 99.0, '2026-03-11')");
            statement.executeUpdate("insert into orders values (5, 55.0, '2026-03-11')");
        }
    }

    private String taskYaml(Path sourceDb, Path targetDb, Path reportsDir) {
        return ""
                + "task:\n"
                + "  name: data_audit_demo\n"
                + "boundary:\n"
                + "  type: job_finish\n"
                + "  reference: latest\n"
                + "source:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + yamlPath(sourceDb) + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "target:\n"
                + "  type: jdbc\n"
                + "  url: jdbc:sqlite:" + yamlPath(targetDb) + "\n"
                + "  table: orders\n"
                + "  options:\n"
                + "    dialect: postgres\n"
                + "object:\n"
                + "  key:\n"
                + "    - order_id\n"
                + "  columns:\n"
                + "    - order_id\n"
                + "    - amount\n"
                + "    - dt\n"
                + "output:\n"
                + "  dir: " + yamlPath(reportsDir) + "\n"
                + "  value_mode: raw\n";
    }

    private String yamlPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
