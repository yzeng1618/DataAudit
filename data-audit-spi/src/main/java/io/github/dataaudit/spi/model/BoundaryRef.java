package io.github.dataaudit.spi.model;

public class BoundaryRef {
    public String type;
    public String reference;
    public String fingerprint;
    public boolean stable = true;
    public String detail;

    public String getType() {
        return type;
    }

    public String getReference() {
        return reference;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public boolean isStable() {
        return stable;
    }

    public String getDetail() {
        return detail;
    }
}
