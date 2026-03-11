package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class DiffResult {
    public boolean consistent = true;
    public String rootCause = "consistent";
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

    public static class DiffSample {
        public String type;
        public String key;
        public String sourceValue;
        public String targetValue;
        public String segmentKey;

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

        public String getSegmentKey() {
            return segmentKey;
        }
    }
}
