// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.it.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class SecondLayerFixtureBuilder {
    private SecondLayerFixtureBuilder() {
    }

    static {
        loadSqliteDriver();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: SecondLayerFixtureBuilder <scenario> <sourcePath> <targetPath>");
        }

        String scenario = args[0];
        Path sourcePath = Paths.get(args[1]);
        Path targetPath = Paths.get(args[2]);

        if ("postgres_simulated_jdbc".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            resetSqlite(targetPath, true);
            seedConsistentSmall(sourcePath, targetPath);
            return;
        }
        if ("mysql_simulated_jdbc".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            resetSqlite(targetPath, true);
            seedConsistentSmall(sourcePath, targetPath);
            return;
        }
        if ("hive_jdbc_partitioned".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            resetSqlite(targetPath, true);
            seedPartitionMismatch(sourcePath, targetPath);
            return;
        }
        if ("doris_jdbc_result_diff".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            resetSqlite(targetPath, true);
            seedSmallDiff(sourcePath, targetPath);
            return;
        }
        if ("iceberg_metadata_first".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            seedIcebergSource(sourcePath);
            resetIcebergConsistentTable(targetPath);
            return;
        }
        if ("jdbc_to_iceberg_consistent".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            seedIcebergSource(sourcePath);
            resetIcebergConsistentTable(targetPath);
            return;
        }
        if ("jdbc_to_iceberg_diff".equalsIgnoreCase(scenario)) {
            resetSqlite(sourcePath, true);
            seedIcebergSource(sourcePath);
            resetIcebergValueDiffTable(targetPath);
            return;
        }
        if ("iceberg_to_jdbc_partitioned".equalsIgnoreCase(scenario)) {
            resetIcebergPartitionedTable(sourcePath);
            resetSqlite(targetPath, true);
            seedPartitionMismatchTarget(targetPath);
            return;
        }
        throw new IllegalArgumentException("Unsupported scenario: " + scenario);
    }

    private static void resetSqlite(Path dbPath, boolean withPrimaryKey) throws Exception {
        Files.deleteIfExists(dbPath);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            if (withPrimaryKey) {
                statement.executeUpdate("create table orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
            } else {
                statement.executeUpdate("create table orders(order_id integer, status text, amount decimal(10,2), dt text)");
            }
        }
    }

    private static void seedConsistentSmall(Path sourceDb, Path targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
    }

    private static void seedSmallDiff(Path sourceDb, Path targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
    }

    private static void seedPartitionMismatch(Path sourceDb, Path targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(sourceDb, 3, "paid", "30.00", "2026-03-11");
        insert(sourceDb, 4, "closed", "40.00", "2026-03-11");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
        insert(targetDb, 3, "paid", "30.00", "2026-03-11");
        insert(targetDb, 4, "closed", "40.00", "2026-03-11");
    }

    private static void seedIcebergSource(Path sourceDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
    }

    private static void seedPartitionMismatchTarget(Path targetDb) throws Exception {
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
        insert(targetDb, 3, "paid", "30.00", "2026-03-11");
    }

    private static void resetIcebergConsistentTable(Path tableLocation) throws Exception {
        IcebergFixtureSupport.resetOrdersTable(tableLocation, java.util.Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10"),
                IcebergFixtureSupport.order(2, "new", "20.00", "2026-03-10")
        ));
    }

    private static void resetIcebergValueDiffTable(Path tableLocation) throws Exception {
        IcebergFixtureSupport.resetOrdersTable(tableLocation, java.util.Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10"),
                IcebergFixtureSupport.order(2, "new", "99.99", "2026-03-10")
        ));
    }

    private static void resetIcebergPartitionedTable(Path tableLocation) throws Exception {
        IcebergFixtureSupport.resetOrdersTable(tableLocation, java.util.Arrays.asList(
                IcebergFixtureSupport.order(1, "paid", "10.00", "2026-03-10"),
                IcebergFixtureSupport.order(2, "new", "20.00", "2026-03-10"),
                IcebergFixtureSupport.order(3, "paid", "30.00", "2026-03-11")
        ));
    }

    private static void insert(Path dbPath, int orderId, String status, String amount, String dt) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            String sql = String.format(
                    "insert into orders(order_id, status, amount, dt) values (%d, '%s', %s, '%s')",
                    orderId, status, amount, dt
            );
            statement.executeUpdate(sql);
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
