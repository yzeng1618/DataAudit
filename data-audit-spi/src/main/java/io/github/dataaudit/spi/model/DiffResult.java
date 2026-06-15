package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class DiffResult {
    public boolean consistent = true;
    public String rootCause = "consistent";
    public boolean sampled;
    public boolean resourceBounded;
    public boolean limitExceeded;
    public String limitType;
    public String fallbackReason;
    public List<DiffSample> samples = new ArrayList<>();

    public boolean isConsistent() {
        return consistent;
    }

    public String getRootCause() {
        return rootCause;
    }

    public List<DiffSample> getSamples() {
        return samples;
    }

    public boolean isResourceBounded() {
        return resourceBounded;
    }

    public boolean isLimitExceeded() {
        return limitExceeded;
    }

    public String getLimitType() {
        return limitType;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public static class DiffSample {
        public String type;
        public String key;
        public String sourceValue;
        public String targetValue;
        public String sliceKey;

        public String getType() {
            return type;
        }

        public String getKey() {
            return key;
        }

        public String getSourceValue() {
            return sourceValue;
        }

        public String getTargetValue() {
            return targetValue;
        }

        public String getSliceKey() {
            return sliceKey;
        }
    }
}
