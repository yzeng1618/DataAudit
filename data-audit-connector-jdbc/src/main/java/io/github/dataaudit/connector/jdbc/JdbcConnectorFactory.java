package io.github.dataaudit.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dataaudit.core.NormalizationService;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.SchemaReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JdbcConnectorFactory implements ConnectorFactory {
    private final SqlDialectResolver dialectResolver = new SqlDialectResolver();

    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public boolean supports(TaskFileSpec.EndpointSpec endpointSpec) {
        return endpointSpec != null && "jdbc".equalsIgnoreCase(endpointSpec.type);
    }

    @Override
    public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
        SqlDialect dialect = dialectResolver.resolve(endpointSpec);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(endpointSpec.url);
        config.setUsername(endpointSpec.username);
        config.setPassword(endpointSpec.password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setInitializationFailTimeout(-1);
        HikariDataSource dataSource = new HikariDataSource(config);

        JdbcEndpoint endpoint = new JdbcEndpoint(spec, endpointSpec, dataSource, dialect);
        CapabilityDescriptor capabilityDescriptor = new CapabilityDescriptor();
        capabilityDescriptor.connectorType = "jdbc";
        capabilityDescriptor.supportsPartitionPrune = true;
        capabilityDescriptor.supportsColumnProjection = true;
        capabilityDescriptor.supportsMetadataStats = false;
        capabilityDescriptor.supportsSnapshotBoundary = false;
        capabilityDescriptor.supportsSignalPushdown = false;
        capabilityDescriptor.supportsGroupedSignalPushdown = false;
        capabilityDescriptor.supportsNativeMetadata = false;
        capabilityDescriptor.sourceLoadPolicy = "conservative";
        capabilityDescriptor.attributes.put("dialect", dialect.getClass().getSimpleName());
        return new ConnectorBundle(capabilityDescriptor, endpoint, endpoint, endpoint, endpoint, null, dataSource::close);
    }

    static final class JdbcEndpoint implements SchemaReader, SignalReader, RowStreamReader, MetadataReader {
        private static final Logger LOG = LoggerFactory.getLogger(JdbcEndpoint.class);

        private final TaskFileSpec spec;
        private final TaskFileSpec.EndpointSpec endpointSpec;
        private final HikariDataSource dataSource;
        private final SqlDialect dialect;
        private final NormalizationService normalizationService = new NormalizationService();
        private SchemaModel schemaCache;

        JdbcEndpoint(TaskFileSpec spec,
                     TaskFileSpec.EndpointSpec endpointSpec,
                     HikariDataSource dataSource,
                     SqlDialect dialect) {
            this.spec = spec;
            this.endpointSpec = endpointSpec;
            this.dataSource = dataSource;
            this.dialect = dialect;
        }

        @Override
        public SchemaModel readSchema() throws Exception {
            if (schemaCache != null) {
                return schemaCache;
            }
            String sql = selectSql(new ReadRequest(), true);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyStatementTuning(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    SchemaModel schema = new SchemaModel();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        SchemaModel.Column column = new SchemaModel.Column();
                        column.name = metadata.getColumnLabel(index);
                        column.type = dialect.normalizeType(metadata.getColumnTypeName(index));
                        column.nullable = metadata.isNullable(index) != ResultSetMetaData.columnNoNulls;
                        schema.columns.add(column);
                    }
                    this.schemaCache = schema;
                    return schema;
                }
            }
        }

        @Override
        public SummaryMetrics readSummary(ReadRequest request) throws Exception {
            SummaryMetrics metrics = new SummaryMetrics();
            final long[] checksum = new long[2];
            scanRows(request, row -> {
                metrics.rowCount++;
                long hash = hash64(normalizationService.canonicalRow(normalizationService.normalizeRow(spec, row)));
                checksum[0] += hash;
                checksum[1] ^= hash;
            });
            metrics.checksum = Long.toUnsignedString(checksum[0]) + ":" + Long.toUnsignedString(checksum[1]);
            return metrics;
        }

        @Override
        public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) throws Exception {
            Set<String> sliceValues = listSliceValues(sliceColumn, request);
            List<SliceSignal> signals = new ArrayList<>();
            for (String value : sliceValues) {
                ReadRequest sliceRequest = cloneRequest(request);
                sliceRequest.sliceColumn = sliceColumn;
                sliceRequest.sliceValue = value;
                SummaryMetrics summary = readSummary(sliceRequest);
                SliceSignal signal = new SliceSignal();
                signal.sliceKey = sliceColumn + "=" + value;
                signal.sliceType = sliceColumn;
                signal.rowCount = summary.rowCount;
                signal.checksum = summary.checksum;
                signals.add(signal);
            }
            return signals;
        }

        @Override
        public void scanRows(ReadRequest request, RowVisitor visitor) throws Exception {
            String sql = selectSql(request, false);
            long startedAt = System.nanoTime();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyStatementTuning(statement);
                bindSlice(statement, request);
                LOG.info("JDBC read start [{}]: {}", endpointLabel(), sqlPreview(sql));
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    int progressIntervalRows = readIntOption("progress_log_interval_rows", 1000);
                    long rows = 0L;
                    while (resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                            row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
                        }
                        if (!matchesSample(row, request)) {
                            continue;
                        }
                        visitor.accept(row);
                        rows++;
                        if (progressIntervalRows > 0 && rows % progressIntervalRows == 0) {
                            LOG.info("JDBC read progress [{}]: fetched {} rows", endpointLabel(), rows);
                        }
                    }
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    LOG.info("JDBC read complete [{}]: rows={}, elapsedMs={}", endpointLabel(), rows, elapsedMillis);
                }
            }
        }

        @Override
        public MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.boundary = resolveBoundary(boundarySpec);
            snapshot.schema = readSchema();
            snapshot.attributes.put("connector", "jdbc");
            snapshot.attributes.put("dialect", dialect.getClass().getSimpleName());
            return snapshot;
        }

        @Override
        public BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
            BoundaryRef ref = new BoundaryRef();
            ref.type = boundarySpec.type;
            ref.reference = boundarySpec.reference;
            ref.stable = !"snapshot".equalsIgnoreCase(boundarySpec.type);
            ref.fingerprint = fingerprint(boundarySpec.type + "|" + boundarySpec.reference + "|" + endpointSpec.table + "|" + endpointSpec.query);
            ref.detail = ref.stable ? "jdbc-logical-boundary" : "snapshot boundary is not supported for jdbc";
            return ref;
        }

        private Set<String> listSliceValues(String sliceColumn, ReadRequest request) throws Exception {
            String physicalColumn = resolvePhysicalColumn(sliceColumn);
            String sql = "select distinct " + dialect.quoteIdentifier(physicalColumn) + " from " + baseSql()
                    + " where " + dialect.quoteIdentifier(physicalColumn) + " is not null order by 1";
            Set<String> values = new LinkedHashSet<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyStatementTuning(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        values.add(String.valueOf(resultSet.getObject(1)));
                    }
                }
            }
            return values;
        }

        private String selectSql(ReadRequest request, boolean schemaOnly) {
            String projection = "*";
            if (request.columns != null && !request.columns.isEmpty()) {
                List<String> projectedColumns = resolveProjectionColumns(request.columns);
                List<String> columns = new ArrayList<>();
                for (String column : projectedColumns) {
                    columns.add(dialect.quoteIdentifier(column));
                }
                if (!columns.isEmpty()) {
                    projection = String.join(", ", columns);
                }
            }
            StringBuilder builder = new StringBuilder("select ").append(projection).append(" from ").append(baseSql()).append(" where 1=1");
            if (request.sliceColumn != null && request.sliceValue != null) {
                builder.append(" and ").append(dialect.buildSegmentPredicate(resolvePhysicalColumn(request.sliceColumn)));
            }
            if (schemaOnly) {
                builder.append(" and 1=0");
            }
            if (!schemaOnly && request.limit != null) {
                return dialect.buildLimitedQuery(builder.toString(), request.limit);
            }
            return builder.toString();
        }

        private void bindSlice(PreparedStatement statement, ReadRequest request) throws SQLException {
            if (request.sliceColumn != null && request.sliceValue != null) {
                statement.setString(1, request.sliceValue);
            }
        }

        private void applyStatementTuning(PreparedStatement statement) throws SQLException {
            int queryTimeoutSeconds = readIntOption("query_timeout_seconds", 0);
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            int fetchSize = readIntOption("fetch_size", 0);
            if (fetchSize > 0) {
                statement.setFetchSize(fetchSize);
            }
        }

        private int readIntOption(String optionKey, int defaultValue) {
            if (endpointSpec.options == null) {
                return defaultValue;
            }
            Object value = endpointSpec.options.get(optionKey);
            if (value == null) {
                return defaultValue;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private String baseSql() {
            if (endpointSpec.query != null && !endpointSpec.query.trim().isEmpty()) {
                return "(" + endpointSpec.query + ") data_audit_subq";
            }
            return endpointSpec.table;
        }

        private boolean matchesSample(Map<String, Object> row, ReadRequest request) {
            if (request.sampleColumn == null || request.sampleModulo == null || request.sampleRemainder == null) {
                return true;
            }
            Object value = readColumnValue(row, request.sampleColumn);
            if (value == null) {
                return false;
            }
            long hash = hash64(String.valueOf(value));
            return Math.floorMod(hash, request.sampleModulo) == request.sampleRemainder;
        }

        private ReadRequest cloneRequest(ReadRequest request) {
            ReadRequest clone = new ReadRequest();
            clone.columns.addAll(request.columns);
            clone.boundaryType = request.boundaryType;
            clone.boundaryReference = request.boundaryReference;
            clone.limit = request.limit;
            clone.sampleColumn = request.sampleColumn;
            clone.sampleModulo = request.sampleModulo;
            clone.sampleRemainder = request.sampleRemainder;
            clone.bucketCount = request.bucketCount;
            clone.bucketId = request.bucketId;
            return clone;
        }

        private String endpointLabel() {
            return endpointSpec.type + ":" + (endpointSpec.table != null && !endpointSpec.table.trim().isEmpty() ? endpointSpec.table : "query");
        }

        private String sqlPreview(String sql) {
            String compact = sql.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            return compact.length() <= 200 ? compact : compact.substring(0, 200) + "...";
        }

        private String fingerprint(String value) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        }

        private long hash64(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                long result = 0L;
                for (int index = 0; index < Long.BYTES; index++) {
                    result = (result << 8) | (bytes[index] & 0xffL);
                }
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to hash row content", e);
            }
        }

        private List<String> resolveProjectionColumns(List<String> requestedColumns) {
            try {
                Set<String> availableColumns = actualColumnNames();
                List<String> resolved = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                for (String requested : requestedColumns) {
                    String column = resolveColumn(availableColumns, requested);
                    if (column != null && seen.add(column)) {
                        resolved.add(column);
                    }
                }
                return resolved;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to resolve JDBC projection columns", e);
            }
        }

        private String resolvePhysicalColumn(String requestedColumn) {
            if (requestedColumn == null || requestedColumn.isEmpty()) {
                return requestedColumn;
            }
            try {
                Set<String> availableColumns = actualColumnNames();
                String resolved = resolveColumn(availableColumns, requestedColumn);
                return resolved == null ? requestedColumn : resolved;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to resolve JDBC column " + requestedColumn, e);
            }
        }

        private Object readColumnValue(Map<String, Object> row, String requestedColumn) {
            if (row.containsKey(requestedColumn)) {
                return row.get(requestedColumn);
            }
            String resolved = resolvePhysicalColumn(requestedColumn);
            return row.get(resolved);
        }

        private Set<String> actualColumnNames() throws Exception {
            Set<String> columns = new LinkedHashSet<>();
            for (SchemaModel.Column column : readSchema().columns) {
                columns.add(column.name);
            }
            return columns;
        }

        private String resolveColumn(Set<String> availableColumns, String requestedColumn) {
            if (availableColumns.contains(requestedColumn)) {
                return requestedColumn;
            }
            String mappedColumn = spec.semantics.ddl.renameMapping.get(requestedColumn);
            if (mappedColumn != null && availableColumns.contains(mappedColumn)) {
                return mappedColumn;
            }
            for (Map.Entry<String, String> entry : spec.semantics.ddl.renameMapping.entrySet()) {
                if (requestedColumn.equals(entry.getValue()) && availableColumns.contains(entry.getKey())) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }
}
