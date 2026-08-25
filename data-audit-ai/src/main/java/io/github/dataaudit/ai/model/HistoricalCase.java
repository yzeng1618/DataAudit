// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.util.ArrayList;
import java.util.List;

public class HistoricalCase {
    public String id;
    public String title;
    public String sourceType;
    public String targetType;
    public String syncMode;
    public String writeMode;
    public List<String> symptoms = new ArrayList<>();
    public List<String> evidencePatterns = new ArrayList<>();
    public List<String> likelyCauses = new ArrayList<>();
    public List<String> recommendedChecks = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
}
