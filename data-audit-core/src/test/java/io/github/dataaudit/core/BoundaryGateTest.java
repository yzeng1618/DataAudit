// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.BoundaryStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundaryGateTest {
    @Test
    void shouldMarkStableBoundaryAsStable() {
        BoundaryRef boundaryRef = new BoundaryRef();
        boundaryRef.type = "job_finish";
        boundaryRef.reference = "latest";
        boundaryRef.stable = true;

        assertEquals(BoundaryStatus.STABLE, new BoundaryGate().evaluate(boundaryRef).status);
    }

    @Test
    void shouldMarkUnstableBoundaryAsUnstable() {
        BoundaryRef boundaryRef = new BoundaryRef();
        boundaryRef.type = "snapshot";
        boundaryRef.reference = "latest";
        boundaryRef.stable = false;

        assertEquals(BoundaryStatus.UNSTABLE, new BoundaryGate().evaluate(boundaryRef).status);
    }
}
