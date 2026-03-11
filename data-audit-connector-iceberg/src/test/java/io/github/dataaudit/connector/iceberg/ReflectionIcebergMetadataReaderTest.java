package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionIcebergMetadataReaderTest {
    @Test
    void shouldResolveSnapshotAndReadSchemaFromLocalIcebergTable() throws Exception {
        Path tempDir = Files.createTempDirectory("recon-iceberg-reader");
        Path tableLocation = tempDir.resolve("orders");

        Schema schema = new Schema(
                Types.NestedField.required(1, "order_id", Types.LongType.get()),
                Types.NestedField.optional(2, "status", Types.StringType.get())
        );
        PartitionSpec spec = PartitionSpec.unpartitioned();
        HadoopTables tables = new HadoopTables(new Configuration());
        Table table = tables.create(schema, spec, tableLocation.toString());

        Path dataFilePath = tableLocation.resolve("data-file.parquet");
        Files.createDirectories(tableLocation);
        Files.write(dataFilePath, new byte[]{0});
        DataFile dataFile = DataFiles.builder(spec)
                .withPath(dataFilePath.toString().replace("\\", "/"))
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(1)
                .withRecordCount(1)
                .build();
        table.newAppend().appendFile(dataFile).commit();

        TaskFileSpec.EndpointSpec endpointSpec = new TaskFileSpec.EndpointSpec();
        endpointSpec.type = "iceberg";
        endpointSpec.location = tableLocation.toString();
        endpointSpec.table = "orders";

        TaskFileSpec.BoundarySpec boundarySpec = new TaskFileSpec.BoundarySpec();
        boundarySpec.type = "snapshot";
        boundarySpec.reference = "latest";

        ReflectionIcebergMetadataReader reader = new ReflectionIcebergMetadataReader(endpointSpec);
        BoundaryRef boundaryRef = reader.resolveBoundary(boundarySpec);
        MetadataSnapshot snapshot = reader.readMetadata(boundarySpec);

        assertTrue(boundaryRef.stable);
        assertEquals("snapshot", boundaryRef.type);
        assertEquals("iceberg", snapshot.attributes.get("connector"));
        assertNotNull(snapshot.attributes.get("snapshotId"));
        assertFalse(snapshot.schema.columns.isEmpty());
        assertFalse(snapshot.segmentHints.isEmpty());
    }
}
