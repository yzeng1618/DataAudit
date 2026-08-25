// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TableProfile {
    public String artifactVersion = "1";
    public String artifactType = "table_profile";
    public String producer = "data-audit-ai";
    public String schemaVersion = "data-audit-table-profile-v1";
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public String profileVersion = "alpha-1";
    public EndpointProfile source = new EndpointProfile();
    public EndpointProfile target = new EndpointProfile();
    public List<ColumnProfile> columns = new ArrayList<>();
    public StatisticsProfile statistics = new StatisticsProfile();
    public List<SampleProfile> samples = new ArrayList<>();
    public SyncContext syncContext = new SyncContext();
    public BoundaryProfile boundary = new BoundaryProfile();
    public Overrides overrides = new Overrides();
    public List<String> retrievalHints = new ArrayList<>();
    public List<String> evidence = new ArrayList<>();
    public List<String> missingInformation = new ArrayList<>();
    public Map<String, Object> metadata = new LinkedHashMap<>();

    public static class EndpointProfile {
        public String type;
        public String catalog;
        public String schema;
        public String table;
        public String query;
        public Map<String, Object> options = new LinkedHashMap<>();
    }

    public static class ColumnProfile {
        public String name;
        public String type = "unknown";
        public String comment;
        public Boolean nullable;
        public Integer precision;
        public Integer scale;
        public List<String> evidence = new ArrayList<>();
    }

    public static class StatisticsProfile {
        public Long estimatedRows;
        public Long estimatedBytes;
        public Map<String, Long> distinctCount = new LinkedHashMap<>();
        public Map<String, Long> nullCount = new LinkedHashMap<>();
        public Map<String, String> minValues = new LinkedHashMap<>();
        public Map<String, String> maxValues = new LinkedHashMap<>();
        public boolean limitedCollection = true;
        public int maxSampleRows = 20;
        public int maxSampleFields = 20;
        public long timeoutMillis = 3000L;
    }

    public static class SampleProfile {
        public String field;
        public boolean masked = true;
        public String pattern;
        public String hash;
        public String summary;
    }

    public static class SyncContext {
        public String syncMode;
        public String writeMode;
        public String timezone;
        public Map<String, Object> attributes = new LinkedHashMap<>();
    }

    public static class BoundaryProfile {
        public String type;
        public String reference;
        public String gracePeriod;
    }

    public static class Overrides {
        public List<String> primaryKeys = new ArrayList<>();
        public List<String> partitionFields = new ArrayList<>();
        public Map<String, Integer> decimalScale = new LinkedHashMap<>();
        public String timezone;
        public String writeMode;
        public String syncMode;
    }
}
