import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class SampleSqliteSeeder {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: SampleSqliteSeeder <scenario> <sourceDb> <targetDb>");
        }
        String scenario = args[0];
        String sourceDb = args[1];
        String targetDb = args[2];

        if ("consistent_small".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedConsistentSmall(sourceDb, targetDb);
            return;
        }
        if ("small_diff".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedSmallDiff(sourceDb, targetDb);
            return;
        }
        if ("partition_mismatch".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedPartitionMismatch(sourceDb, targetDb);
            return;
        }
        if ("keyless_multiset".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, false);
            reset(targetDb, false);
            seedKeylessMultiset(sourceDb, targetDb);
            return;
        }
        if ("schema_mismatch".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            resetSchemaMismatch(sourceDb, targetDb);
            seedSchemaMismatch(sourceDb, targetDb);
            return;
        }
        if ("unstable_snapshot_jdbc".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedConsistentSmall(sourceDb, targetDb);
            return;
        }
        if ("ddl_rename_compatible".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            resetRenameCompatible(sourceDb, targetDb);
            seedRenameCompatible(sourceDb, targetDb);
            return;
        }
        if ("delete_hard_delete_mismatch".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedDeleteHardDeleteMismatch(sourceDb, targetDb);
            return;
        }
        if ("bucket_mismatch".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, true);
            reset(targetDb, true);
            seedBucketMismatch(sourceDb, targetDb);
            return;
        }
        if ("keyless_large_consistent".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, false);
            reset(targetDb, false);
            seedKeylessLargeConsistent(sourceDb, targetDb);
            return;
        }
        if ("keyless_large_inconclusive".equalsIgnoreCase(scenario)) {
            Class.forName("org.sqlite.JDBC");
            reset(sourceDb, false);
            reset(targetDb, false);
            seedKeylessLargeInconclusive(sourceDb, targetDb);
            return;
        }
        throw new IllegalArgumentException("Unsupported scenario: " + scenario);
    }

    private static void reset(String dbPath, boolean withPrimaryKey) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists orders");
            if (withPrimaryKey) {
                statement.executeUpdate("create table orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
            } else {
                statement.executeUpdate("create table orders(order_id integer, status text, amount decimal(10,2), dt text)");
            }
        }
    }

    private static void resetSchemaMismatch(String sourceDb, String targetDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourceDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists orders");
            statement.executeUpdate("create table orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + targetDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists orders");
            statement.executeUpdate("create table orders(order_id integer primary key, status text, amount decimal(10,2), dt text, extra_note text)");
        }
    }

    private static void resetRenameCompatible(String sourceDb, String targetDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourceDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists orders");
            statement.executeUpdate("create table orders(order_id integer primary key, status text, old_amount decimal(10,2), dt text)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + targetDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists orders");
            statement.executeUpdate("create table orders(order_id integer primary key, status text, amount decimal(10,2), dt text)");
        }
    }

    private static void seedConsistentSmall(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
    }

    private static void seedSmallDiff(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
    }

    private static void seedPartitionMismatch(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(sourceDb, 3, "paid", "30.00", "2026-03-11");
        insert(sourceDb, 4, "closed", "40.00", "2026-03-11");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "99.99", "2026-03-10");
        insert(targetDb, 3, "paid", "30.00", "2026-03-11");
        insert(targetDb, 4, "closed", "40.00", "2026-03-11");
    }

    private static void seedKeylessMultiset(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
    }

    private static void seedSchemaMismatch(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + targetDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into orders(order_id, status, amount, dt, extra_note) values (1, 'paid', 10.00, '2026-03-10', 'ok')");
            statement.executeUpdate("insert into orders(order_id, status, amount, dt, extra_note) values (2, 'new', 20.00, '2026-03-10', 'ok')");
        }
    }

    private static void seedRenameCompatible(String sourceDb, String targetDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourceDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into orders(order_id, status, old_amount, dt) values (1, 'paid', 10.00, '2026-03-10')");
        }
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
    }

    private static void seedDeleteHardDeleteMismatch(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "cancelled", "20.00", "2026-03-10");
    }

    private static void seedBucketMismatch(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(sourceDb, 3, "paid", "30.00", "2026-03-11");
        insert(sourceDb, 4, "closed", "40.00", "2026-03-11");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
        insert(targetDb, 3, "paid", "31.11", "2026-03-11");
        insert(targetDb, 4, "closed", "40.00", "2026-03-11");
    }

    private static void seedKeylessLargeConsistent(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-11");
        insert(sourceDb, 2, "new", "20.00", "2026-03-11");

        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 1, "paid", "10.00", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-11");
        insert(targetDb, 2, "new", "20.00", "2026-03-11");
    }

    private static void seedKeylessLargeInconclusive(String sourceDb, String targetDb) throws Exception {
        insert(sourceDb, 1, "paid", "10.00", "2026-03-10");
        insert(sourceDb, 2, "new", "20.00", "2026-03-10");
        insert(sourceDb, 3, "paid", "30.00", "2026-03-11");
        insert(sourceDb, 7, "closed", "40.00", "2026-03-11");

        insert(targetDb, 1, "paid", "99.99", "2026-03-10");
        insert(targetDb, 2, "new", "20.00", "2026-03-10");
        insert(targetDb, 3, "paid", "31.11", "2026-03-11");
        insert(targetDb, 7, "closed", "40.00", "2026-03-11");
    }

    private static void insert(String dbPath, int orderId, String status, String amount, String dt) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            String sql = String.format(
                    "insert into orders(order_id, status, amount, dt) values (%d, '%s', %s, '%s')",
                    orderId, status, amount, dt
            );
            statement.executeUpdate(sql);
        }
    }
}
