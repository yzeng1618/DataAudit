package io.github.dataaudit.it.support;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class IcebergFixtureSupport {
    private IcebergFixtureSupport() {
    }

    public static Table resetOrdersTable(Path tableLocation, List<OrderRow> rows) throws Exception {
        deleteRecursively(tableLocation);
        Files.createDirectories(tableLocation);

        Schema schema = ordersSchema();
        PartitionSpec spec = PartitionSpec.unpartitioned();
        HadoopTables tables = new HadoopTables(new Configuration());
        Table table = tables.create(schema, spec, tableLocation.toString());

        GenericAppenderFactory appenderFactory = new GenericAppenderFactory(schema, spec);
        OutputFileFactory outputFileFactory = OutputFileFactory.builderFor(table, 1, 1L)
                .format(FileFormat.PARQUET)
                .build();

        try (DataWriter<Record> writer = appenderFactory.newDataWriter(outputFileFactory.newOutputFile(), FileFormat.PARQUET, null)) {
            for (OrderRow row : rows) {
                writer.write(toRecord(schema, row));
            }
            writer.close();
            table.newAppend().appendFile(writer.toDataFile()).commit();
        }
        return table;
    }

    public static Schema ordersSchema() {
        return new Schema(
                Types.NestedField.required(1, "order_id", Types.LongType.get()),
                Types.NestedField.optional(2, "status", Types.StringType.get()),
                Types.NestedField.optional(3, "amount", Types.DecimalType.of(10, 2)),
                Types.NestedField.optional(4, "dt", Types.StringType.get())
        );
    }

    public static OrderRow order(long orderId, String status, String amount, String dt) {
        return new OrderRow(orderId, status, new BigDecimal(amount), dt);
    }

    private static GenericRecord toRecord(Schema schema, OrderRow row) {
        GenericRecord record = GenericRecord.create(schema);
        record.setField("order_id", row.orderId);
        record.setField("status", row.status);
        record.setField("amount", row.amount);
        record.setField("dt", row.dt);
        return record;
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

    public static final class OrderRow {
        public final long orderId;
        public final String status;
        public final BigDecimal amount;
        public final String dt;

        public OrderRow(long orderId, String status, BigDecimal amount, String dt) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
            this.dt = dt;
        }
    }
}
