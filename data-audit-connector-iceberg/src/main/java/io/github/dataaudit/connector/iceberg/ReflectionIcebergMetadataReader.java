package io.github.dataaudit.connector.iceberg;

import io.github.dataaudit.spi.connector.MetadataReader;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.SchemaModel;
import io.github.dataaudit.spi.model.SegmentDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReflectionIcebergMetadataReader implements MetadataReader {
    private final TaskFileSpec.EndpointSpec endpointSpec;

    public ReflectionIcebergMetadataReader(TaskFileSpec.EndpointSpec endpointSpec) {
        this.endpointSpec = endpointSpec;
    }

    @Override
    public MetadataSnapshot readMetadata(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        Object table = loadTable();
        Object snapshot = resolveSnapshot(table, boundarySpec);
        MetadataSnapshot snapshotModel = new MetadataSnapshot();
        snapshotModel.boundary = resolveBoundary(boundarySpec);
        snapshotModel.schema = readSchema(table);
        snapshotModel.attributes.put("connector", "iceberg");
        snapshotModel.attributes.put("table", tableName());
        if (snapshot != null) {
            snapshotModel.attributes.put("snapshotId", String.valueOf(invoke(snapshot, "snapshotId")));
            Object summary = invoke(snapshot, "summary");
            if (summary instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) summary;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    snapshotModel.attributes.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            snapshotModel.segmentHints.addAll(readManifestHints(table, snapshot));
        }
        return snapshotModel;
    }

    @Override
    public BoundaryRef resolveBoundary(TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        Object table = loadTable();
        Object snapshot = resolveSnapshot(table, boundarySpec);
        BoundaryRef ref = new BoundaryRef();
        ref.type = boundarySpec.type == null ? "snapshot" : boundarySpec.type;
        ref.reference = boundarySpec.reference;
        ref.stable = snapshot != null;
        if (snapshot != null) {
            Object snapshotId = invoke(snapshot, "snapshotId");
            ref.reference = String.valueOf(snapshotId);
            ref.fingerprint = fingerprint("iceberg|" + tableName() + "|" + snapshotId);
            ref.detail = "iceberg-snapshot";
        } else {
            ref.fingerprint = fingerprint("iceberg|" + tableName() + "|missing-snapshot");
            ref.detail = "snapshot not found";
        }
        return ref;
    }

    private Object loadTable() throws Exception {
        if (endpointSpec.location != null && !endpointSpec.location.trim().isEmpty()) {
            Object configuration = newHadoopConfiguration();
            Class<?> tablesClass = Class.forName("org.apache.iceberg.hadoop.HadoopTables");
            Object tables = tablesClass.getConstructor(configuration.getClass()).newInstance(configuration);
            return invoke(tables, "load", endpointSpec.location);
        }

        Object configuration = newHadoopConfiguration();
        Object catalog = buildCatalog(configuration);
        Class<?> tableIdentifierClass = Class.forName("org.apache.iceberg.catalog.TableIdentifier");
        Object tableIdentifier = tableIdentifierClass.getMethod("parse", String.class).invoke(null, tableName());
        return invoke(catalog, "loadTable", tableIdentifier);
    }

    private Object buildCatalog(Object configuration) throws Exception {
        Class<?> utilClass = Class.forName("org.apache.iceberg.CatalogUtil");
        Map<String, String> properties = new LinkedHashMap<>();
        if (endpointSpec.warehouse != null) {
            properties.put("warehouse", endpointSpec.warehouse);
        }
        if (endpointSpec.uri != null) {
            properties.put("uri", endpointSpec.uri);
        }
        String catalogName = endpointSpec.catalog == null ? "default" : endpointSpec.catalog;
        String catalogImpl = catalogImplClassName();

        try {
            Method buildCatalog = utilClass.getMethod("buildIcebergCatalog", String.class, Map.class, Class.forName("org.apache.hadoop.conf.Configuration"));
            return buildCatalog.invoke(null, catalogName, properties, configuration);
        } catch (NoSuchMethodException ignored) {
            Method loadCatalog = utilClass.getMethod("loadCatalog", String.class, String.class, Map.class, Object.class);
            return loadCatalog.invoke(null, catalogImpl, catalogName, properties, configuration);
        }
    }

    private Object resolveSnapshot(Object table, TaskFileSpec.BoundarySpec boundarySpec) throws Exception {
        String reference = boundarySpec.reference;
        if (reference == null || reference.trim().isEmpty() || "latest".equalsIgnoreCase(reference)) {
            return invoke(table, "currentSnapshot");
        }
        try {
            long snapshotId = Long.parseLong(reference);
            return invoke(table, "snapshot", snapshotId);
        } catch (NumberFormatException ignored) {
            return invoke(table, "currentSnapshot");
        }
    }

    private SchemaModel readSchema(Object table) throws Exception {
        Object schema = invoke(table, "schema");
        Object columns = invoke(schema, "columns");
        List<?> list = asList(columns);
        SchemaModel schemaModel = new SchemaModel();
        for (Object columnObject : list) {
            SchemaModel.Column column = new SchemaModel.Column();
            column.name = String.valueOf(invoke(columnObject, "name"));
            Object type = invoke(columnObject, "type");
            column.type = type == null ? "unknown" : type.toString();
            try {
                column.nullable = Boolean.TRUE.equals(invoke(columnObject, "isOptional"));
            } catch (ReflectiveOperationException ignored) {
                column.nullable = true;
            }
            schemaModel.columns.add(column);
        }
        return schemaModel;
    }

    private List<SegmentDescriptor> readManifestHints(Object table, Object snapshot) throws Exception {
        List<SegmentDescriptor> hints = new ArrayList<>();
        if (snapshot == null) {
            return hints;
        }
        try {
            Object io = invoke(table, "io");
            Object manifests = invoke(snapshot, "allManifests", io);
            List<?> list = asList(manifests);
            int index = 0;
            for (Object manifest : list) {
                SegmentDescriptor descriptor = new SegmentDescriptor();
                descriptor.segmentKey = "manifest=" + index;
                descriptor.reason = "iceberg_manifest_hint";
                Object path = invoke(manifest, "path");
                descriptor.sourceDigest = path == null ? null : String.valueOf(path);
                try {
                    descriptor.targetDigest = String.valueOf(invoke(manifest, "partitionSpecId"));
                } catch (ReflectiveOperationException ignored) {
                    descriptor.targetDigest = null;
                }
                hints.add(descriptor);
                index++;
            }
        } catch (ReflectiveOperationException ignored) {
            SegmentDescriptor descriptor = new SegmentDescriptor();
            descriptor.segmentKey = "snapshot=" + invoke(snapshot, "snapshotId");
            descriptor.reason = "iceberg_snapshot_hint";
            hints.add(descriptor);
        }
        return hints;
    }

    private Object newHadoopConfiguration() throws Exception {
        Class<?> configurationClass = Class.forName("org.apache.hadoop.conf.Configuration");
        return configurationClass.getConstructor().newInstance();
    }

    private String tableName() {
        String namespace = endpointSpec.namespace != null && !endpointSpec.namespace.trim().isEmpty() ? endpointSpec.namespace : endpointSpec.database;
        if (namespace == null || namespace.trim().isEmpty()) {
            return endpointSpec.table;
        }
        return namespace + "." + endpointSpec.table;
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

    private List<?> asList(Object object) {
        if (object instanceof List<?>) {
            return (List<?>) object;
        }
        if (object != null && object.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            int length = Array.getLength(object);
            for (int index = 0; index < length; index++) {
                list.add(Array.get(object, index));
            }
            return list;
        }
        return new ArrayList<>();
    }

    private Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return invoke(target, methodName, new Object[0]);
    }

    private Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, args);
        if (!method.canAccess(target)) {
            method.setAccessible(true);
        }
        return method.invoke(target, args);
    }

    private Method findMethod(Class<?> type, String methodName, Object... args) throws NoSuchMethodException {
        for (Method method : collectMethods(type)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != args.length) {
                continue;
            }
            boolean compatible = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!isCompatible(parameterTypes[index], args[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + methodName);
    }

    private List<Method> collectMethods(Class<?> type) {
        Set<Method> methods = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                methods.add(method);
            }
            for (Class<?> candidateInterface : current.getInterfaces()) {
                collectInterfaceMethods(candidateInterface, methods);
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            methods.add(method);
        }
        return new ArrayList<>(methods);
    }

    private void collectInterfaceMethods(Class<?> candidateInterface, Set<Method> methods) {
        for (Method method : candidateInterface.getDeclaredMethods()) {
            methods.add(method);
        }
        for (Class<?> nested : candidateInterface.getInterfaces()) {
            collectInterfaceMethods(nested, methods);
        }
    }

    private boolean isCompatible(Class<?> parameterType, Object argument) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        Class<?> argumentType = argument.getClass();
        if (parameterType.isPrimitive()) {
            return primitiveWrapper(parameterType).isAssignableFrom(argumentType);
        }
        return parameterType.isAssignableFrom(argumentType);
    }

    private Class<?> primitiveWrapper(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return Boolean.class;
        }
        if (primitiveType == byte.class) {
            return Byte.class;
        }
        if (primitiveType == char.class) {
            return Character.class;
        }
        if (primitiveType == short.class) {
            return Short.class;
        }
        if (primitiveType == int.class) {
            return Integer.class;
        }
        if (primitiveType == long.class) {
            return Long.class;
        }
        if (primitiveType == float.class) {
            return Float.class;
        }
        if (primitiveType == double.class) {
            return Double.class;
        }
        return primitiveType;
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
