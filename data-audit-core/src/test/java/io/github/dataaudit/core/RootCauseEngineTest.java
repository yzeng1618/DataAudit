// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootCauseEngineTest {
    @Test
    void shouldPrioritizeBoundaryDrift() {
        assertEquals("boundary_drift", new RootCauseEngine().resolve(true, true, true, true));
    }

    @Test
    void shouldPrioritizeRowCountMismatchOverOtherDataIssues() {
        assertEquals("row_count_mismatch", new RootCauseEngine().resolve(false, true, true, true));
    }
}
