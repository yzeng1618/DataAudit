// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class SummaryMetrics {
    public long rowCount;
    public Map<String, Long> nullCount = new LinkedHashMap<>();
    public Map<String, String> minValues = new LinkedHashMap<>();
    public Map<String, String> maxValues = new LinkedHashMap<>();
    public Map<String, Long> distinctCount = new LinkedHashMap<>();
    public String checksum;
}

