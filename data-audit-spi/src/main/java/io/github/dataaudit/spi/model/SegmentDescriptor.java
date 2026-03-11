package io.github.dataaudit.spi.model;

public class SegmentDescriptor {
    public String segmentKey;
    public String segmentColumn;
    public String segmentValue;
    public String reason;
    public String sourceDigest;
    public String targetDigest;

    public String getSegmentKey() {
        return segmentKey;
    }

    public String getSegmentColumn() {
        return segmentColumn;
    }

    public String getSegmentValue() {
        return segmentValue;
    }

    public String getReason() {
        return reason;
    }

    public String getSourceDigest() {
        return sourceDigest;
    }

    public String getTargetDigest() {
        return targetDigest;
    }
}
