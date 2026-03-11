package io.github.dataaudit.it.support;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;

public final class SecondLayerFixtureBuilder {
    private SecondLayerFixtureBuilder() {
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
            resetIcebergTable(targetPath);
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

    private static void resetIcebergTable(Path tableLocation) throws Exception {
        deleteRecursively(tableLocation);
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }
}
