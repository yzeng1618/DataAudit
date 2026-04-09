package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.BoundaryStatus;

public class BoundaryGate {
    public BoundaryContext evaluate(BoundaryRef boundaryRef) {
        BoundaryContext context = new BoundaryContext();
        context.boundaryRef = boundaryRef;
        context.status = boundaryRef != null && boundaryRef.stable ? BoundaryStatus.STABLE : BoundaryStatus.UNSTABLE;
        return context;
    }
}
