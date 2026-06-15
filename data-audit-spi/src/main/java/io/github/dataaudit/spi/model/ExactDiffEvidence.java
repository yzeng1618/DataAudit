package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class ExactDiffEvidence {
    public boolean completed;
    public DiffResult diff = new DiffResult();
    public SamplingSummary samplingSummary = new SamplingSummary();
    public boolean limitExceeded;
    public String limitType;
    public List<ProgressEvent> progressEvents = new ArrayList<>();
}
