package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.core.NormalizationService;
import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.connector.RoutingSignalReader;
import io.github.dataaudit.spi.connector.SchemaReader;
import io.github.dataaudit.spi.connector.SignalReader;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SliceSignal;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IcebergDataReader implements SchemaReader, SignalReader, RoutingSignalReader, RowStreamReader {
    private final TaskFileSpec spec;
    private final IcebergTableSupport tableSupport;
    private final NormalizationService normalizationService = new NormalizationService();

    IcebergDataReader(TaskFileSpec spec, IcebergTableSupport tableSupport) {
        this.spec = spec;
        this.tableSupport = tableSupport;
    }

    @Override
    public SchemaModel readSchema() {
        return tableSupport.readSchemaModel(tableSupport.loadTable().schema());
    }

    @Override
    public SummaryMetrics readSummary(ReadRequest request) throws Exception {
        SummaryMetrics metrics = new SummaryMetrics();
        final long[] checksum = new long[2];
        scanRows(request, row -> {
            metrics.rowCount++;
            long hash = tableSupport.hash64(normalizationService.canonicalRow(normalizationService.normalizeRow(spec, row)));
            checksum[0] += hash;
            checksum[1] ^= hash;
        });
        metrics.checksum = Long.toUnsignedString(checksum[0]) + ":" + Long.toUnsignedString(checksum[1]);
        return metrics;
    }

    @Override
    public List<SliceSignal> readSliceSignals(String sliceColumn, ReadRequest request) throws Exception {
        List<SliceSignal> signals = new ArrayList<>();
        for (String value : listSliceValues(sliceColumn, request)) {
            ReadRequest sliceRequest = new ReadRequest();
            sliceRequest.columns.addAll(request.columns);
            sliceRequest.boundaryType = request.boundaryType;
            sliceRequest.boundaryReference = request.boundaryReference;
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
    public List<SliceSignal> readRoutingSignals(ReadRequest request) throws Exception {
        String routingColumn = tableSupport.resolveRoutingColumn(spec);
        if (routingColumn == null || routingColumn.isEmpty()) {
            return new ArrayList<>();
        }
        List<SliceSignal> signals = readSliceSignals(routingColumn, request);
        for (SliceSignal signal : signals) {
            String value = signal.sliceKey;
            int separator = value.indexOf('=');
            if (separator >= 0) {
                value = value.substring(separator + 1);
            }
            signal.sliceKey = "routing=" + value;
            signal.sliceType = "routing";
        }
        return signals;
    }

    @Override
    public void scanRows(ReadRequest request, RowVisitor visitor) throws Exception {
        Table table = tableSupport.loadTable();
        IcebergGenerics.ScanBuilder builder = IcebergGenerics.read(table);
        Long snapshotId = tableSupport.resolveSnapshotId(table, request.boundaryType, request.boundaryReference);
        if (snapshotId != null) {
            builder.useSnapshot(snapshotId);
        }
        List<String> projectedColumns = resolveProjectionColumns(table.schema(), request.columns);
        if (!projectedColumns.isEmpty()) {
            builder.select(projectedColumns);
        }
        if (request.sliceColumn != null && request.sliceValue != null) {
            String physicalSliceColumn = resolvePhysicalColumn(table.schema(), request.sliceColumn);
            builder.where(Expressions.equal(physicalSliceColumn, convertLiteral(table.schema(), physicalSliceColumn, request.sliceValue)));
        }

        try (CloseableIterable<Record> iterable = builder.build()) {
            long remaining = request.limit == null ? Long.MAX_VALUE : request.limit;
            for (Record record : iterable) {
                if (remaining-- <= 0) {
                    break;
                }
                Map<String, Object> row = asRow(record, projectedColumns);
                if (!matchesSample(row, request)) {
                    continue;
                }
                visitor.accept(row);
            }
        }
    }

    List<String> listSliceValues(String column, ReadRequest request) throws Exception {
        ReadRequest sliceRequest = new ReadRequest();
        String physicalColumn = resolvePhysicalColumn(tableSupport.loadTable().schema(), column);
        sliceRequest.columns.add(physicalColumn);
        sliceRequest.boundaryType = request.boundaryType;
        sliceRequest.boundaryReference = request.boundaryReference;
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : collectRows(sliceRequest)) {
            Object value = row.get(physicalColumn);
            String text = value == null ? null : String.valueOf(value);
            if (text != null && !values.contains(text)) {
                values.add(text);
            }
        }
        return values;
    }

    private Map<String, Object> asRow(Record record, List<String> requestedColumns) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (requestedColumns != null && !requestedColumns.isEmpty()) {
            for (String column : requestedColumns) {
                row.put(column, record.getField(column));
            }
            return row;
        }
        List<Types.NestedField> fields = record.struct().fields();
        for (int index = 0; index < fields.size(); index++) {
            row.put(fields.get(index).name(), record.get(index));
        }
        return row;
    }

    private List<Map<String, Object>> collectRows(ReadRequest request) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        scanRows(request, rows::add);
        return rows;
    }

    private boolean matchesSample(Map<String, Object> row, ReadRequest request) {
        if (request.sampleColumn == null || request.sampleModulo == null || request.sampleRemainder == null) {
            return true;
        }
        Object value = readColumnValue(row, request.sampleColumn);
        if (value == null) {
            return false;
        }
        long hash = tableSupport.hash64(String.valueOf(value));
        return Math.floorMod(hash, request.sampleModulo) == request.sampleRemainder;
    }

    private Object convertLiteral(Schema schema, String column, String value) {
        Types.NestedField field = schema.findField(column);
        if (field == null) {
            return value;
        }
        Type type = field.type();
        switch (type.typeId()) {
            case INTEGER:
                return Integer.parseInt(value);
            case LONG:
                return Long.parseLong(value);
            case FLOAT:
                return Float.parseFloat(value);
            case DOUBLE:
                return Double.parseDouble(value);
            case BOOLEAN:
                return Boolean.parseBoolean(value);
            case DECIMAL:
                return new BigDecimal(value);
            default:
                return value;
        }
    }

    private List<String> resolveProjectionColumns(Schema schema, List<String> requestedColumns) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> availableColumns = new LinkedHashSet<>();
        for (Types.NestedField field : schema.columns()) {
            availableColumns.add(field.name());
        }
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

    private String resolvePhysicalColumn(Schema schema, String requestedColumn) {
        if (requestedColumn == null || requestedColumn.isEmpty()) {
            return requestedColumn;
        }
        Set<String> availableColumns = new LinkedHashSet<>();
        for (Types.NestedField field : schema.columns()) {
            availableColumns.add(field.name());
        }
        String resolved = resolveColumn(availableColumns, requestedColumn);
        return resolved == null ? requestedColumn : resolved;
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
