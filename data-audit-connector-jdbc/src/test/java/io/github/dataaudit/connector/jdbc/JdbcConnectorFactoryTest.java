// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.jdbc;

import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectorFactoryTest {
    @TempDir
    Path tempDir;

    private final JdbcConnectorFactory factory = new JdbcConnectorFactory();

    @Test
    void shouldSupportOnlyJdbcEndpoints() {
        assertTrue(factory.supports(endpointForType("jdbc")));
        assertTrue(factory.supports(endpointForType("JDBC")));
        assertFalse(factory.supports(endpointForType("trino")));
        assertFalse(factory.supports(null));
        assertEquals("jdbc", factory.type());
    }

    @Test
    void shouldBeDiscoverableThroughServiceLoader() {
        boolean found = false;
        for (ConnectorFactory candidate : ServiceLoader.load(ConnectorFactory.class)) {
            if (candidate instanceof JdbcConnectorFactory) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services must register JdbcConnectorFactory");
    }

    @Test
    void shouldReadSchemaFromRealDatabase() throws Exception {
        Path db = seedDatabase("schema.db", "20.00");
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint(db))) {
            SchemaModel schema = bundle.getSchemaReader().readSchema();
            Set<String> names = new LinkedHashSet<>();
            for (SchemaModel.Column column : schema.columns) {
                names.add(column.name);
            }
            assertEquals(Set.of("order_id", "status", "amount", "dt"), names);
        }
    }

    @Test
    void shouldProjectOnlyResolvableColumns() throws Exception {
        Path db = seedDatabase("projection.db", "20.00");
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint(db))) {
            ReadRequest request = new ReadRequest();
            request.columns.add("order_id");
            request.columns.add("ghost_column");
            List<Map<String, Object>> rows = collectRows(bundle, request);
            assertEquals(4, rows.size());
            assertEquals(Set.of("order_id"), rows.get(0).keySet());
        }
    }

    @Test
    void shouldResolveColumnsThroughRenameMapping() throws Exception {
        Path db = seedDatabase("rename.db", "20.00");
        TaskFileSpec spec = new TaskFileSpec();
        spec.semantics.ddl.renameMapping.put("amount_v2", "amount");
        try (ConnectorBundle bundle = factory.open(spec, endpoint(db))) {
            ReadRequest request = new ReadRequest();
            request.columns.add("order_id");
            request.columns.add("amount_v2");
            List<Map<String, Object>> rows = collectRows(bundle, request);
            assertEquals(4, rows.size());
            assertTrue(rows.get(0).containsKey("amount"), "renamed column resolves to its physical name");
            assertFalse(rows.get(0).containsKey("amount_v2"));
        }
    }

    @Test
    void shouldComputeDeterministicChecksumAndDetectChanges() throws Exception {
        Path db = seedDatabase("checksum-a.db", "20.00");
        Path changed = seedDatabase("checksum-b.db", "99.99");
        ReadRequest request = new ReadRequest();
        request.columns.add("order_id");
        request.columns.add("amount");
        try (ConnectorBundle first = factory.open(new TaskFileSpec(), endpoint(db));
             ConnectorBundle second = factory.open(new TaskFileSpec(), endpoint(changed))) {
            SummaryMetrics once = first.getSignalReader().readSummary(request);
            SummaryMetrics again = first.getSignalReader().readSummary(request);
            SummaryMetrics different = second.getSignalReader().readSummary(request);
            assertEquals(4, once.rowCount);
            assertEquals(once.checksum, again.checksum);
            assertNotEquals(once.checksum, different.checksum);
        }
    }

    @Test
    void shouldGroupSliceSignalsByPartitionColumn() throws Exception {
        Path db = seedDatabase("slices.db", "20.00");
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint(db))) {
            ReadRequest request = new ReadRequest();
            request.columns.add("order_id");
            request.columns.add("amount");
            List<SliceSignal> signals = bundle.getSignalReader().readSliceSignals("dt", request);
            assertEquals(2, signals.size());
            assertEquals("dt=2026-03-10", signals.get(0).sliceKey);
            assertEquals(2, signals.get(0).rowCount);
            assertEquals("dt=2026-03-11", signals.get(1).sliceKey);
            assertEquals(2, signals.get(1).rowCount);
        }
    }

    @Test
    void shouldExcludeRowsWithNullSampleColumn() throws Exception {
        Path db = seedDatabase("sample.db", "20.00");
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint(db))) {
            ReadRequest request = new ReadRequest();
            request.sampleColumn = "status";
            request.sampleModulo = 1;
            request.sampleRemainder = 0;
            List<Map<String, Object>> rows = collectRows(bundle, request);
            assertEquals(3, rows.size(), "the row with a null sample value is excluded");
        }
    }

    @Test
    void shouldMarkSnapshotBoundaryUnstable() throws Exception {
        Path db = seedDatabase("boundary.db", "20.00");
        try (ConnectorBundle bundle = factory.open(new TaskFileSpec(), endpoint(db))) {
            TaskFileSpec.BoundarySpec snapshot = new TaskFileSpec.BoundarySpec();
            snapshot.type = "snapshot";
            snapshot.reference = "latest";
            BoundaryRef unstable = bundle.getMetadataReader().resolveBoundary(snapshot);
            assertFalse(unstable.stable);

            TaskFileSpec.BoundarySpec jobFinish = new TaskFileSpec.BoundarySpec();
            jobFinish.type = "job_finish";
            jobFinish.reference = "latest";
            BoundaryRef stable = bundle.getMetadataReader().resolveBoundary(jobFinish);
            assertTrue(stable.stable);
            assertEquals(64, stable.fingerprint.length(), "fingerprint is a sha-256 hex digest");
        }
    }

    private List<Map<String, Object>> collectRows(ConnectorBundle bundle, ReadRequest request) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        bundle.getRowStreamReader().scanRows(request, rows::add);
        return rows;
    }

    private TaskFileSpec.EndpointSpec endpointForType(String type) {
        TaskFileSpec.EndpointSpec spec = new TaskFileSpec.EndpointSpec();
        spec.type = type;
        return spec;
    }

    private TaskFileSpec.EndpointSpec endpoint(Path db) {
        TaskFileSpec.EndpointSpec spec = new TaskFileSpec.EndpointSpec();
        spec.type = "jdbc";
        spec.url = "jdbc:sqlite:" + db;
        spec.table = "orders";
        spec.options.put("dialect", "postgres");
        return spec;
    }

    private Path seedDatabase(String name, String amountForOrderTwo) throws Exception {
        Path db = tempDir.resolve(name);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table orders (order_id integer primary key, status text, amount text, dt text)");
            statement.executeUpdate("insert into orders values (1, 'PAID', '10.00', '2026-03-10')");
            statement.executeUpdate("insert into orders values (2, 'PAID', '" + amountForOrderTwo + "', '2026-03-10')");
            statement.executeUpdate("insert into orders values (3, 'NEW', '7.50', '2026-03-11')");
            statement.executeUpdate("insert into orders values (4, null, '3.25', '2026-03-11')");
        }
        return db;
    }
}
