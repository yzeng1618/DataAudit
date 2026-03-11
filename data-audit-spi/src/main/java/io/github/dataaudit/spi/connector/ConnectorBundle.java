package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.CapabilityDescriptor;

public class ConnectorBundle implements AutoCloseable {
    private final CapabilityDescriptor capabilityDescriptor;
    private final DataReader dataReader;
    private final MetadataReader metadataReader;
    private final EvidenceReader evidenceReader;
    private final AutoCloseable closeable;

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           DataReader dataReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader) {
        this(capabilityDescriptor, dataReader, metadataReader, evidenceReader, null);
    }

    public ConnectorBundle(CapabilityDescriptor capabilityDescriptor,
                           DataReader dataReader,
                           MetadataReader metadataReader,
                           EvidenceReader evidenceReader,
                           AutoCloseable closeable) {
        this.capabilityDescriptor = capabilityDescriptor;
        this.dataReader = dataReader;
        this.metadataReader = metadataReader;
        this.evidenceReader = evidenceReader;
        this.closeable = closeable;
    }

    public CapabilityDescriptor getCapabilityDescriptor() {
        return capabilityDescriptor;
    }

    public DataReader getDataReader() {
        return dataReader;
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
