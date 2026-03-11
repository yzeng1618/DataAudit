package io.github.dataaudit.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.connector.ConnectorFactory;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.CapabilityDescriptor;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public ConnectorBundle open(TaskFileSpec.EndpointSpec endpointSpec) {
        SqlDialect dialect = dialectResolver.resolve(endpointSpec);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(endpointSpec.url);
        config.setUsername(endpointSpec.username);
        config.setPassword(endpointSpec.password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setInitializationFailTimeout(-1);
        HikariDataSource dataSource = new HikariDataSource(config);

        JdbcEndpoint endpoint = new JdbcEndpoint(endpointSpec, dataSource, dialect);
        CapabilityDescriptor capabilityDescriptor = new CapabilityDescriptor();
        capabilityDescriptor.connectorType = "jdbc";
        capabilityDescriptor.supportsPartitionPrune = true;
        capabilityDescriptor.supportsColumnProjection = true;
        capabilityDescriptor.supportsMetadataStats = false;
        capabilityDescriptor.supportsSnapshotBoundary = false;
        capabilityDescriptor.supportsKeyedDiff = true;
        capabilityDescriptor.supportsKeylessMultiset = true;
        capabilityDescriptor.attributes.put("dialect", dialect.getClass().getSimpleName());
        return new ConnectorBundle(capabilityDescriptor, endpoint, endpoint, null, dataSource::close);
    }

    static final class JdbcEndpoint implements io.github.dataaudit.spi.connector.DataReader, io.github.dataaudit.spi.connector.MetadataReader {
        private final TaskFileSpec.EndpointSpec endpointSpec;
        private final HikariDataSource dataSource;
        private final SqlDialect dialect;

        JdbcEndpoint(TaskFileSpec.EndpointSpec endpointSpec, HikariDataSource dataSource, SqlDialect dialect) {
            this.endpointSpec = endpointSpec;
            this.dataSource = dataSource;
            this.dialect = dialect;
        }

        @Override
        public List<Map<String, Object>> readRows(ReadRequest request) throws Exception {
            String sql = selectSql(request, false);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bindSegment(statement, request);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    while (resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                            row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
                        }
                        rows.add(row);
                    }
                }
            }
            return rows;
        }

        @Override
        public List<String> listSegmentValues(String column, ReadRequest request) throws Exception {
            String base = baseSql();
            String sql = "select distinct " + dialect.quoteIdentifier(column) + " from " + base
                    + " where " + dialect.quoteIdentifier(column) + " is not null order by 1";
            List<String> values = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(String.valueOf(resultSet.getObject(1)));
                }
            }
            return values;
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

        private SchemaModel readSchema() throws SQLException {
            SchemaModel schema = new SchemaModel();
            String sql = selectSql(new ReadRequest(), true);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    SchemaModel.Column column = new SchemaModel.Column();
                    column.name = metadata.getColumnLabel(index);
                    column.type = dialect.normalizeType(metadata.getColumnTypeName(index));
                    column.nullable = metadata.isNullable(index) != ResultSetMetaData.columnNoNulls;
                    schema.columns.add(column);
                }
            }
            return schema;
        }

        private String selectSql(ReadRequest request, boolean schemaOnly) {
            String projection = "*";
            if (request.columns != null && !request.columns.isEmpty()) {
                List<String> columns = new ArrayList<>();
                for (String column : request.columns) {
                    columns.add(dialect.quoteIdentifier(column));
                }
                projection = String.join(", ", columns);
            }
            StringBuilder builder = new StringBuilder("select ").append(projection).append(" from ").append(baseSql()).append(" where 1=1");
            if (request.segmentColumn != null && request.segmentValue != null) {
                builder.append(" and ").append(dialect.buildSegmentPredicate(request.segmentColumn));
            }
            if (schemaOnly) {
                builder.append(" and 1=0");
            }
            if (!schemaOnly && request.limit != null) {
                return dialect.buildLimitedQuery(builder.toString(), request.limit);
            }
            return builder.toString();
        }

        private void bindSegment(PreparedStatement statement, ReadRequest request) throws SQLException {
            if (request.segmentColumn != null && request.segmentValue != null) {
                statement.setString(1, request.segmentValue);
            }
        }

        private String baseSql() {
            if (endpointSpec.query != null && !endpointSpec.query.trim().isEmpty()) {
                return "(" + endpointSpec.query + ") recon_subq";
            }
            return endpointSpec.table;
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
    }
}
