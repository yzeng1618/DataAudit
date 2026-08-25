// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

public class SliceDescriptor {
    public String sliceKey;
    public String sliceType;
    public Long rowEstimate;
    public boolean drilldownable = true;
    public String reason;

    public String getSliceKey() {
        return sliceKey;
    }

    public String getSliceType() {
        return sliceType;
    }

    public Long getRowEstimate() {
        return rowEstimate;
    }

    public boolean isDrilldownable() {
        return drilldownable;
    }

    public String getReason() {
        return reason;
    }
}
