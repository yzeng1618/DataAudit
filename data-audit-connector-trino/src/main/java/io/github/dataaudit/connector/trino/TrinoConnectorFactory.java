// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.trino;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dataaudit.core.NormalizationService;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.RoutingSignalReader;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

public class TrinoConnectorFactory implements ConnectorFactory {
    @Override
    public String type() {
        return "trino";
    }

    @Override
    public boolean supports(TaskFileSpec.EndpointSpec endpointSpec) {
        return endpointSpec != null
                && ("sql".equalsIgnoreCase(endpointSpec.type) || "trino".equalsIgnoreCase(endpointSpec.type));
    }

    @Override
    public ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
        if (spec.queryConnector == null || !"trino".equalsIgnoreCase(spec.queryConnector.type)) {
            throw new IllegalArgumentException("query_connector.type=trino is required for sql/trino endpoints");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(spec, endpointSpec));
        config.setUsername(spec.queryConnector.user);
        config.setPassword(spec.queryConnector.password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setInitializationFailTimeout(-1);
        if (spec.queryConnector.sessionProperties != null && !spec.queryConnector.sessionProperties.isEmpty()) {
            StringJoiner joiner = new StringJoiner(";");
            for (Map.Entry<String, String> entry : spec.queryConnector.sessionProperties.entrySet()) {
                joiner.add(entry.getKey() + ":" + entry.getValue());
            }
            config.addDataSourceProperty("sessionProperties", joiner.toString());
        }
        HikariDataSource dataSource = new HikariDataSource(config);

        TrinoEndpoint endpoint = new TrinoEndpoint(spec, endpointSpec, dataSource);
        CapabilityDescriptor capabilityDescriptor = new CapabilityDescriptor();
        capabilityDescriptor.connectorType = "trino";
        capabilityDescriptor.supportsPartitionPrune = true;
        capabilityDescriptor.supportsColumnProjection = true;
        capabilityDescriptor.supportsMetadataStats = false;
        capabilityDescriptor.supportsSnapshotBoundary = false;
        capabilityDescriptor.supportsSignalPushdown = true;
        capabilityDescriptor.supportsGroupedSignalPushdown = true;
        capabilityDescriptor.supportsRoutingSignalPushdown = true;
        capabilityDescriptor.supportsNativeMetadata = false;
        capabilityDescriptor.sourceLoadPolicy = "balanced";
        return new ConnectorBundle(capabilityDescriptor, endpoint, endpoint, endpoint, endpoint, endpoint, null, dataSource::close);
    }

    String buildJdbcUrl(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec) {
        String raw = spec.queryConnector.uri;
        boolean ssl = raw != null && raw.startsWith("https://");
        if (raw.startsWith("jdbc:trino://")) {
            raw = raw.substring("jdbc:trino://".length());
        } else if (raw.startsWith("trino://")) {
            raw = raw.substring("trino://".length());
        } else if (raw.startsWith("http://")) {
            raw = raw.substring("http://".length());
        } else if (raw.startsWith("https://")) {
            raw = raw.substring("https://".length());
        }
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            raw = raw.substring(0, slash);
        }
        String catalog = endpointSpec.catalog != null ? endpointSpec.catalog : spec.queryConnector.catalog;
        String schema = endpointSpec.schema != null ? endpointSpec.schema : spec.queryConnector.schema;
        StringBuilder url = new StringBuilder("jdbc:trino://").append(raw);
        if (catalog != null && !catalog.isEmpty()) {
            url.append('/').append(catalog);
            if (schema != null && !schema.isEmpty()) {
                url.append('/').append(schema);
            }
        }
        if (ssl) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append("SSL=true");
        }
        return url.toString();
    }

    static final class TrinoEndpoint implements SchemaReader, SignalReader, RoutingSignalReader, RowStreamReader, MetadataReader {
        private static final Logger LOG = LoggerFactory.getLogger(TrinoEndpoint.class);

        private final TaskFileSpec spec;
        private final TaskFileSpec.EndpointSpec endpointSpec;
        private final HikariDataSource dataSource;
        private final NormalizationService normalizationService = new NormalizationService();
        private SchemaModel schemaCache;

        TrinoEndpoint(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpointSpec, HikariDataSource dataSource) {
            this.spec = spec;
            this.endpointSpec = endpointSpec;
            this.dataSource = dataSource;
        }

        @Override
        public SchemaModel readSchema() throws Exception {
            if (schemaCache != null) {
                return schemaCache;
            }
            String sql = selectSql(new ReadRequest(), true);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = executeQuery(statement)) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                SchemaModel schema = new SchemaModel();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    SchemaModel.Column column = new SchemaModel.Column();
                    column.name = metadata.getColumnLabel(index);
                    column.type = metadata.getColumnTypeName(index);
                    column.nullable = metadata.isNullable(index) != ResultSetMetaData.columnNoNulls;
                    schema.columns.add(column);
                }
                this.schemaCache = schema;
                return schema;
            }
        }

        @Override
        public SummaryMetrics readSummary(ReadRequest request) throws Exception {
            if (requiresNormalizedSignal()) {
                return readNormalizedSummary(request);
            }
            List<String> columns = projectionColumns(request);
            String sql = "select count(*) as row_count, " + checksumExpr(columns)
                    + " as checksum from " + baseSql() + filterClause(request, true);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bindSlice(statement, request);
                try (ResultSet resultSet = executeQuery(statement)) {
                    SummaryMetrics metrics = new SummaryMetrics();
                    if (resultSet.next()) {
                        metrics.rowCount = resultSet.getLong("row_count");
                        metrics.checksum = resultSet.getString("checksum");
                    }
                    return metrics;
                }
            }
        }

        @Override
        public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) throws Exception {
            String physicalSliceColumn = resolvePhysicalColumn(sliceColumn);
            if (requiresNormalizedSignal()) {
                return readNormalizedSignals(sliceColumn, physicalSliceColumn, request, sliceColumn);
            }
            List<String> columns = projectionColumns(request);
            String sql = "select " + quoteIdentifier(physicalSliceColumn) + " as slice_value, count(*) as row_count, "
                    + checksumExpr(columns) + " as checksum from " + baseSql()
                    + " where " + quoteIdentifier(physicalSliceColumn) + " is not null group by 1 order by 1";
            return executeSignalQuery(sql, sliceColumn, sliceColumn, "slice_value");
        }

        @Override
        public List<SliceSignal> readRoutingSignals(ReadRequest request) throws Exception {
            String routingColumn = resolveRoutingColumn();
            if (routingColumn == null) {
                return new ArrayList<>();
            }
            String physicalRoutingColumn = resolvePhysicalColumn(routingColumn);
            if (requiresNormalizedSignal()) {
                return readNormalizedSignals(routingColumn, physicalRoutingColumn, request, "routing");
            }
            List<String> columns = projectionColumns(request);
            String sql = "select " + quoteIdentifier(physicalRoutingColumn) + " as routing_value, count(*) as row_count, "
                    + checksumExpr(columns) + " as checksum from " + baseSql()
                    + " where " + quoteIdentifier(physicalRoutingColumn) + " is not null group by 1 order by 1";
            return executeSignalQuery(sql, "routing", "routing", "routing_value");
        }

        @Override
        public void scanRows(ReadRequest request, RowVisitor visitor) throws Exception {
            String sql = selectSql(request, false);
            long startedAt = System.nanoTime();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyStatementTuning(statement);
                bindSlice(statement, request);
                LOG.info("Trino read start [{}]: {}", endpointLabel(), sqlPreview(sql));
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
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
                    }
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    LOG.info("Trino read complete [{}]: rows={}, elapsedMs={}", endpointLabel(), rows, elapsedMillis);
                }
            }
        }

        @Override
        public MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.boundary = resolveBoundary(boundarySpec);
            snapshot.schema = readSchema();
            snapshot.attributes.put("connector", "trino");
            snapshot.attributes.put("catalog", resolvedCatalog());
            snapshot.attributes.put("schema", resolvedSchema());
            return snapshot;
        }

        @Override
        public BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
            BoundaryRef ref = new BoundaryRef();
            ref.type = boundarySpec.type;
            ref.reference = boundarySpec.reference;
            ref.stable = !"snapshot".equalsIgnoreCase(boundarySpec.type);
            ref.fingerprint = fingerprint(boundarySpec.type + "|" + boundarySpec.reference + "|" + endpointLabel());
            ref.detail = ref.stable ? "trino-logical-boundary" : "snapshot boundary is not supported for trino sql endpoints";
            return ref;
        }

        private String selectSql(ReadRequest request, boolean schemaOnly) throws Exception {
            String projection = "*";
            if (request.columns != null && !request.columns.isEmpty()) {
                List<String> columns = projectionColumns(request);
                if (!columns.isEmpty()) {
                    projection = quotedColumns(columns);
                }
            }
            StringBuilder builder = new StringBuilder("select ").append(projection).append(" from ").append(baseSql()).append(filterClause(request, false));
            if (schemaOnly) {
                builder.append(" and 1=0");
            }
            if (!schemaOnly && request.limit != null) {
                builder.append(" limit ").append(request.limit);
            }
            return builder.toString();
        }

        private String filterClause(ReadRequest request, boolean includeWhereKeyword) {
            if (request.sliceColumn == null || request.sliceValue == null) {
                return includeWhereKeyword ? "" : " where 1=1";
            }
            String prefix = includeWhereKeyword ? " where " : " where 1=1 and ";
            return prefix + quoteIdentifier(resolvePhysicalColumn(request.sliceColumn)) + " = ?";
        }

        private void bindSlice(PreparedStatement statement, ReadRequest request) throws Exception {
            if (request.sliceColumn != null && request.sliceValue != null) {
                statement.setString(1, request.sliceValue);
            }
        }

        private ResultSet executeQuery(PreparedStatement statement) throws Exception {
            applyStatementTuning(statement);
            return statement.executeQuery();
        }

        private void applyStatementTuning(PreparedStatement statement) throws Exception {
            int queryTimeoutSeconds = resourceQueryTimeoutSeconds();
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
        }

        private int resourceQueryTimeoutSeconds() {
            if (spec.resources == null || spec.resources.queryTimeoutMillis == null || spec.resources.queryTimeoutMillis <= 0L) {
                return 0;
            }
            long millis = spec.resources.queryTimeoutMillis;
            long seconds = (millis / 1000L) + (millis % 1000L == 0L ? 0L : 1L);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds));
        }

        private boolean matchesSample(Map<String, Object> row, ReadRequest request) {
            if (request.sampleColumn == null || request.sampleModulo == null || request.sampleRemainder == null) {
                return true;
            }
            Object value = readColumnValue(row, request.sampleColumn);
            if (value == null) {
                return false;
            }
            return Math.floorMod(hash64(String.valueOf(value)), request.sampleModulo) == request.sampleRemainder;
        }

        private String checksumExpr(List<String> columns) {
            List<String> exprs = new ArrayList<>();
            for (String column : columns) {
                exprs.add("coalesce(cast(" + quoteIdentifier(column) + " as varchar), '<null>')");
            }
            if (exprs.isEmpty()) {
                return "checksum('constant')";
            }
            if (exprs.size() == 1) {
                return "checksum(" + exprs.get(0) + ")";
            }
            List<String> args = new ArrayList<>();
            for (int index = 0; index < exprs.size(); index++) {
                if (index > 0) {
                    args.add("'|'");
                }
                args.add(exprs.get(index));
            }
            return "checksum(concat(" + String.join(", ", args) + "))";
        }

        private List<String> projectionColumns(ReadRequest request) throws Exception {
            if (request.columns != null && !request.columns.isEmpty()) {
                return resolveProjectionColumns(request.columns);
            }
            List<String> columns = new ArrayList<>();
            for (SchemaModel.Column column : readSchema().columns) {
                columns.add(column.name);
            }
            return columns;
        }

        private String quotedColumns(List<String> columns) {
            List<String> quoted = new ArrayList<>();
            for (String column : columns) {
                quoted.add(quoteIdentifier(column));
            }
            return String.join(", ", quoted);
        }

        private String baseSql() {
            if (endpointSpec.query != null && !endpointSpec.query.trim().isEmpty()) {
                return "(" + endpointSpec.query + ") data_audit_sql";
            }
            List<String> parts = new ArrayList<>();
            if (resolvedCatalog() != null && !resolvedCatalog().isEmpty()) {
                parts.add(quoteIdentifier(resolvedCatalog()));
            }
            if (resolvedSchema() != null && !resolvedSchema().isEmpty()) {
                parts.add(quoteIdentifier(resolvedSchema()));
            }
            parts.add(quoteIdentifier(endpointSpec.table));
            return String.join(".", parts);
        }

        private String resolvedCatalog() {
            return endpointSpec.catalog != null ? endpointSpec.catalog : spec.queryConnector.catalog;
        }

        private String resolvedSchema() {
            return endpointSpec.schema != null ? endpointSpec.schema : spec.queryConnector.schema;
        }

        private String endpointLabel() {
            return endpointSpec.type + ":" + (endpointSpec.table != null ? endpointSpec.table : "query");
        }

        private String sqlPreview(String sql) {
            String compact = sql.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            return compact.length() <= 200 ? compact : compact.substring(0, 200) + "...";
        }

        private String quoteIdentifier(String value) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
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
                throw new IllegalStateException("Unable to hash trino sample key", e);
            }
        }

        private boolean requiresNormalizedSignal() {
            return !spec.semantics.ddl.renameMapping.isEmpty()
                    || Boolean.TRUE.equals(spec.normalize.trimString)
                    || Boolean.TRUE.equals(spec.normalize.emptyAsNull)
                    || !spec.normalize.caseInsensitiveColumns.isEmpty()
                    || !spec.normalize.decimalScale.isEmpty()
                    || (spec.normalize.timezone != null && !"UTC".equalsIgnoreCase(spec.normalize.timezone));
        }

        private SummaryMetrics readNormalizedSummary(ReadRequest request) throws Exception {
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

        private List<SliceSignal> readNormalizedSignals(String requestedColumn,
                                                        String physicalColumn,
                                                        ReadRequest request,
                                                        String signalType) throws Exception {
            List<SliceSignal> signals = new ArrayList<>();
            for (String value : listSliceValues(physicalColumn)) {
                ReadRequest sliceRequest = cloneRequest(request);
                sliceRequest.sliceColumn = requestedColumn;
                sliceRequest.sliceValue = value;
                SummaryMetrics summary = readNormalizedSummary(sliceRequest);
                SliceSignal signal = new SliceSignal();
                signal.sliceKey = signalType + "=" + value;
                signal.sliceType = signalType;
                signal.rowCount = summary.rowCount;
                signal.checksum = summary.checksum;
                signals.add(signal);
            }
            return signals;
        }

        private List<String> listSliceValues(String physicalSliceColumn) throws Exception {
            String sql = "select distinct " + quoteIdentifier(physicalSliceColumn) + " as slice_value from " + baseSql()
                    + " where " + quoteIdentifier(physicalSliceColumn) + " is not null order by 1";
            List<String> values = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = executeQuery(statement)) {
                while (resultSet.next()) {
                    values.add(resultSet.getString("slice_value"));
                }
            }
            return values;
        }

        private List<SliceSignal> executeSignalQuery(String sql,
                                                     String sliceType,
                                                     String keyPrefix,
                                                     String valueColumn) throws Exception {
            List<SliceSignal> signals = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = executeQuery(statement)) {
                while (resultSet.next()) {
                    SliceSignal signal = new SliceSignal();
                    signal.sliceKey = keyPrefix + "=" + resultSet.getString(valueColumn);
                    signal.sliceType = sliceType;
                    signal.rowCount = resultSet.getLong("row_count");
                    signal.checksum = resultSet.getString("checksum");
                    signals.add(signal);
                }
            }
            return signals;
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

        private List<String> resolveProjectionColumns(List<String> requestedColumns) throws Exception {
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
                throw new IllegalStateException("Unable to resolve Trino column " + requestedColumn, e);
            }
        }

        private Object readColumnValue(Map<String, Object> row, String requestedColumn) {
            if (row.containsKey(requestedColumn)) {
                return row.get(requestedColumn);
            }
            String mappedColumn = spec.semantics.ddl.renameMapping.get(requestedColumn);
            if (mappedColumn != null && row.containsKey(mappedColumn)) {
                return row.get(mappedColumn);
            }
            for (Map.Entry<String, String> entry : spec.semantics.ddl.renameMapping.entrySet()) {
                if (requestedColumn.equals(entry.getValue()) && row.containsKey(entry.getKey())) {
                    return row.get(entry.getKey());
                }
            }
            return null;
        }

        private Set<String> actualColumnNames() throws Exception {
            Set<String> columns = new LinkedHashSet<>();
            for (SchemaModel.Column column : readSchema().columns) {
                columns.add(column.name);
            }
            return columns;
        }

        private String resolveRoutingColumn() {
            if (spec.object == null) {
                return null;
            }
            if (spec.object.routingStrategy != null && !spec.object.routingStrategy.trim().isEmpty()) {
                return spec.object.routingStrategy.trim();
            }
            if (spec.object.partitionBy != null && !spec.object.partitionBy.isEmpty()) {
                return spec.object.partitionBy.get(0);
            }
            if (spec.object.groupBy != null && !spec.object.groupBy.isEmpty()) {
                return spec.object.groupBy.get(0);
            }
            return null;
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
