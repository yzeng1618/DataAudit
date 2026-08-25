// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.util.ArrayList;
import java.util.List;

public class ProfileReview {
    public enum Status {
        CONFIRMED,
        REVIEW_REQUIRED,
        INSUFFICIENT
    }

    public Status status = Status.CONFIRMED;
    public String evidenceQuality = "medium";
    public List<ConfirmationItem> confirmationItems = new ArrayList<>();
    public List<String> impact = new ArrayList<>();
    public List<String> nextActions = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();

    public static class ConfirmationItem {
        public String field;
        public String suggestedValue;
        public double confidence;
        public List<String> evidence = new ArrayList<>();
        public List<String> missingInformation = new ArrayList<>();
        public String impact;
        public String nextAction;
    }
}
