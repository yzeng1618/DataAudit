package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IcebergTableSupport {
    private final TaskFileSpec.EndpointSpec endpointSpec;

    IcebergTableSupport(TaskFileSpec.EndpointSpec endpointSpec) {
        this.endpointSpec = endpointSpec;
    }

    Table loadTable() {
        if (endpointSpec.location != null && !endpointSpec.location.trim().isEmpty()) {
            return new HadoopTables(new Configuration()).load(endpointSpec.location);
        }

        Catalog catalog = buildCatalog();
        return catalog.loadTable(TableIdentifier.parse(tableName()));
    }

    MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        Table table = loadTable();
        Snapshot snapshot = resolveSnapshot(table, boundarySpec);
        MetadataSnapshot snapshotModel = new MetadataSnapshot();
        snapshotModel.boundary = resolveBoundary(table, boundarySpec, snapshot);
        snapshotModel.schema = readSchemaModel(table.schema());
        snapshotModel.attributes.put("connector", "iceberg");
        snapshotModel.attributes.put("table", tableName());
        snapshotModel.attributes.put("partitionSpec", table.spec().toString());
        if (snapshot != null) {
            snapshotModel.attributes.put("snapshotId", String.valueOf(snapshot.snapshotId()));
            Map<String, String> summary = snapshot.summary();
            if (summary != null) {
                snapshotModel.attributes.putAll(summary);
            }
            snapshotModel.sliceHints.addAll(readManifestHints(table, snapshot));
        }
        return snapshotModel;
    }

    BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        Table table = loadTable();
        Snapshot snapshot = resolveSnapshot(table, boundarySpec);
        return resolveBoundary(table, boundarySpec, snapshot);
    }

    Long resolveSnapshotId(Table table, String boundaryType, String boundaryReference) {
        String type = boundaryType == null ? "snapshot" : boundaryType;
        if (!"snapshot".equalsIgnoreCase(type) && !"version".equalsIgnoreCase(type)) {
            return null;
        }
        Snapshot snapshot = resolveSnapshot(table, boundaryReference);
        return snapshot == null ? null : snapshot.snapshotId();
    }

    private BoundaryRef resolveBoundary(Table table, TaskFileSpec.BoundarySpec boundarySpec, Snapshot snapshot) throws Exception {
        BoundaryRef ref = new BoundaryRef();
        ref.type = boundarySpec.type == null ? "snapshot" : boundarySpec.type;
        ref.reference = boundarySpec.reference;
        ref.stable = snapshot != null;
        if (snapshot != null) {
            ref.reference = String.valueOf(snapshot.snapshotId());
            ref.fingerprint = fingerprint("iceberg|" + tableName() + "|" + snapshot.snapshotId());
            ref.detail = "iceberg-snapshot";
        } else {
            ref.fingerprint = fingerprint("iceberg|" + tableName() + "|missing-snapshot");
            ref.detail = "snapshot not found";
        }
        return ref;
    }

    private Catalog buildCatalog() {
        Map<String, String> properties = new LinkedHashMap<>();
        if (endpointSpec.warehouse != null) {
            properties.put("warehouse", endpointSpec.warehouse);
        }
        if (endpointSpec.uri != null) {
            properties.put("uri", endpointSpec.uri);
        }
        String catalogName = endpointSpec.catalog == null ? "default" : endpointSpec.catalog;
        return CatalogUtil.loadCatalog(catalogImplClassName(), catalogName, properties, new Configuration());
    }

    private Snapshot resolveSnapshot(Table table, TaskFileSpec.BoundarySpec boundarySpec) {
        String reference = boundarySpec == null ? null : boundarySpec.reference;
        return resolveSnapshot(table, reference);
    }

    private Snapshot resolveSnapshot(Table table, String reference) {
        String effectiveReference = reference;
        if ((effectiveReference == null || effectiveReference.trim().isEmpty() || "latest".equalsIgnoreCase(effectiveReference))
                && endpointSpec.snapshotId != null && !endpointSpec.snapshotId.trim().isEmpty()) {
            effectiveReference = endpointSpec.snapshotId;
        }
        if (effectiveReference == null || effectiveReference.trim().isEmpty() || "latest".equalsIgnoreCase(effectiveReference)) {
            return table.currentSnapshot();
        }
        try {
            long snapshotId = Long.parseLong(effectiveReference);
            return table.snapshot(snapshotId);
        } catch (NumberFormatException ignored) {
            return table.currentSnapshot();
        }
    }

    SchemaModel readSchemaModel(Schema schema) {
        SchemaModel schemaModel = new SchemaModel();
        for (Types.NestedField field : schema.columns()) {
            SchemaModel.Column column = new SchemaModel.Column();
            column.name = field.name();
            column.type = field.type().toString();
            column.nullable = field.isOptional();
            schemaModel.columns.add(column);
        }
        return schemaModel;
    }

    private List<SliceDescriptor> readManifestHints(Table table, Snapshot snapshot) {
        List<SliceDescriptor> hints = new ArrayList<>();
        if (snapshot == null) {
            return hints;
        }
        List<ManifestFile> manifests = snapshot.allManifests(table.io());
        if (manifests == null || manifests.isEmpty()) {
            SliceDescriptor descriptor = new SliceDescriptor();
            descriptor.sliceKey = "snapshot=" + snapshot.snapshotId();
            descriptor.sliceType = "metadata_hint";
            descriptor.drilldownable = false;
            descriptor.reason = "iceberg_snapshot_hint";
            hints.add(descriptor);
            return hints;
        }

        int index = 0;
        for (ManifestFile manifest : manifests) {
            SliceDescriptor descriptor = new SliceDescriptor();
            descriptor.sliceKey = "manifest=" + index;
            descriptor.sliceType = "metadata_hint";
            descriptor.drilldownable = false;
            descriptor.reason = "iceberg_manifest_hint";
            descriptor.rowEstimate = manifest.addedRowsCount();
            hints.add(descriptor);
            index++;
        }
        return hints;
    }

    String tableName() {
        String namespace = endpointSpec.namespace;
        if (namespace == null || namespace.trim().isEmpty()) {
            return endpointSpec.table;
        }
        return namespace + "." + endpointSpec.table;
    }

    long hash64(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << 8) | (bytes[index] & 0xffL);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash iceberg row content", e);
        }
    }

    private String catalogImplClassName() {
        String catalogType = endpointSpec.catalogType == null ? "hadoop" : endpointSpec.catalogType;
        if ("hive".equalsIgnoreCase(catalogType)) {
            return "org.apache.iceberg.hive.HiveCatalog";
        }
        if ("rest".equalsIgnoreCase(catalogType)) {
            return "org.apache.iceberg.rest.RESTCatalog";
        }
        return "org.apache.iceberg.hadoop.HadoopCatalog";
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
