package io.github.dataaudit.spi.connector;

/**
 * Reserved extension point for evidence-mode readers (for example Hudi
 * timeline, Delta CDF, or CDC change evidence). No methods are defined yet;
 * bundles may pass {@code null} until the contract lands.
 */
public interface EvidenceReader {
}
