package io.github.dataaudit.spi.model;

public enum ProofMode {
    GLOBAL_CHECKSUM,
    EXACT_DIFF,
    GROUPED_CHECKSUM,
    ROUTING_DIGEST,
    XOR_CHECKSUM_PLUS_SAMPLE,
    SAMPLING
}
