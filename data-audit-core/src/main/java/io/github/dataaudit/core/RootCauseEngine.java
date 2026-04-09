package io.github.dataaudit.core;

public class RootCauseEngine {
    public String resolve(boolean boundaryDrift,
                          boolean rowCountMismatch,
                          boolean duplicateOrMissing,
                          boolean valueMismatch) {
        if (boundaryDrift) {
            return "boundary_drift";
        }
        if (rowCountMismatch) {
            return "row_count_mismatch";
        }
        if (duplicateOrMissing) {
            return "duplicate_or_missing";
        }
        if (valueMismatch) {
            return "value_mismatch";
        }
        return null;
    }
}
