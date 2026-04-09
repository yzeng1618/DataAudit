package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.CapabilityDescriptor;

public class ConnectorBundle implements AutoCloseable {
    private final CapabilityDescriptor capabilityDescriptor;
    private final SchemaReader schemaReader;
    private final SignalReader signalReader;
    private final RoutingSignalReader routingSignalReader;
    private final RowStreamReader rowStreamReader;
    private final MetadataReader metadataReader;
    private final EvidenceReader evidenceReader;
    private final AutoCloseable closeable;

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           SchemaReader schemaReader,
                           SignalReader signalReader,
                           RowStreamReader rowStreamReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader) {
        this(capabilityDescriptor, schemaReader, signalReader, null, rowStreamReader, metadataReader, evidenceReader, null);
    }

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           SchemaReader schemaReader,
                           SignalReader signalReader,
                           RoutingSignalReader routingSignalReader,
                           RowStreamReader rowStreamReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader) {
        this(capabilityDescriptor, schemaReader, signalReader, routingSignalReader, rowStreamReader, metadataReader, evidenceReader, null);
    }

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           SchemaReader schemaReader,
                           SignalReader signalReader,
                           RowStreamReader rowStreamReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader,
                           AutoCloseable closeable) {
        this(capabilityDescriptor, schemaReader, signalReader, null, rowStreamReader, metadataReader, evidenceReader, closeable);
    }

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           SchemaReader schemaReader,
                           SignalReader signalReader,
                           RoutingSignalReader routingSignalReader,
                           RowStreamReader rowStreamReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader,
                           AutoCloseable closeable) {
        this.capabilityDescriptor = capabilityDescriptor;
        this.schemaReader = schemaReader;
        this.signalReader = signalReader;
        this.routingSignalReader = routingSignalReader;
        this.rowStreamReader = rowStreamReader;
        this.metadataReader = metadataReader;
        this.evidenceReader = evidenceReader;
        this.closeable = closeable;
    }

    public CapabilityDescriptor getCapabilityDescriptor() {
        return capabilityDescriptor;
    }

    public SchemaReader getSchemaReader() {
        return schemaReader;
    }

    public SignalReader getSignalReader() {
        return signalReader;
    }

    public RoutingSignalReader getRoutingSignalReader() {
        return routingSignalReader;
    }

    public RowStreamReader getRowStreamReader() {
        return rowStreamReader;
    }

    public MetadataReader getMetadataReader() {
        return metadataReader;
    }

    public EvidenceReader getEvidenceReader() {
        return evidenceReader;
    }

    @Override
    public void close() throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }
}
