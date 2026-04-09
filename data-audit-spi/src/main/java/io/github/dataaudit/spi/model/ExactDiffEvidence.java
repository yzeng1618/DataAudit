package io.github.dataaudit.spi.model;

public class ExactDiffEvidence {
    public boolean completed;
    public DiffResult diff = new DiffResult();
    public SamplingSummary samplingSummary = new SamplingSummary();
}
