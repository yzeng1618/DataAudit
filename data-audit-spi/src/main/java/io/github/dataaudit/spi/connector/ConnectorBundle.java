package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.CapabilityDescriptor;

/**
 * Everything the engine may use on one opened endpoint: a
 * {@link CapabilityDescriptor} advertising what the connector can do, and one
 * reader per access pattern. A reader must be non-null whenever the matching
 * capability flag is set (for example {@code supportsRoutingSignalPushdown}
 * implies a {@link RoutingSignalReader}); the engine consults the descriptor
 * before dereferencing optional readers.
 *
 * <p>The bundle owns the endpoint's resources: the engine calls
 * {@link #close()} exactly once when the run is finished, which delegates to
 * the {@code closeable} passed at construction (for example a connection
 * pool).
 */
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
