package io.github.dataaudit.spi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskFileSpec {
    public TaskSpec task = new TaskSpec();
    public BoundarySpec boundary = new BoundarySpec();
    public EndpointSpec source = new EndpointSpec();
    public EndpointSpec target = new EndpointSpec();
    public ObjectSpec object = new ObjectSpec();
    public PlannerSpec planner = new PlannerSpec();
    public NormalizationSpec normalization = new NormalizationSpec();
    public CompareSpec compare = new CompareSpec();
    public DmlSpec dml = new DmlSpec();
    public DdlSpec ddl = new DdlSpec();
    public EvidenceSpec evidence = new EvidenceSpec();
    public OutputSpec output = new OutputSpec();
    public StateSpec state = new StateSpec();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskSpec {
        public String name;
        public String description;
        public String mode = "post_check";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoundarySpec {
        public String type = "job_finish";
        public String reference = "latest";
        public String gracePeriod;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointSpec {
        public String type;
        public String url;
        public String username;
        public String password;
        public String table;
        public String query;
        public String catalog;
        public String catalogType;
        public String warehouse;
        public String uri;
        public String database;
        public String namespace;
        public String snapshotId;
        public String location;
        public Map<String, Object> options = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObjectSpec {
        public List<String> key = new ArrayList<>();
        public ColumnsSpec columns = new ColumnsSpec();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnsSpec {
        public List<String> include = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlannerSpec {
        public String mode = "auto";
        public PlannerHintsSpec hints = new PlannerHintsSpec();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlannerHintsSpec {
        public String objectClass = "auto";
        public Long estimatedRows;
        public List<String> partitionKeys = new ArrayList<>();
        public Long maxExactRows;
        public Boolean forceExactDiff = Boolean.FALSE;
        public Boolean preferMetadata = Boolean.TRUE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NormalizationSpec {
        public String timezone = "UTC";
        public Boolean trimString = Boolean.FALSE;
        public Boolean emptyAsNull = Boolean.FALSE;
        public List<String> caseInsensitiveColumns = new ArrayList<>();
        public Map<String, Integer> decimalScale = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompareSpec {
        public List<String> levels = defaultLevels();
        public SummarySpec summary = new SummarySpec();
        public SegmentSpec segment = new SegmentSpec();
        public DiffSpec diff = new DiffSpec();

        private static List<String> defaultLevels() {
            List<String> values = new ArrayList<>();
            values.add("schema");
            values.add("summary");
            values.add("segment");
            values.add("diff");
            return values;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummarySpec {
        public List<String> metrics = defaultMetrics();
        public HashSpec hash = new HashSpec();

        private static List<String> defaultMetrics() {
            List<String> values = new ArrayList<>();
            values.add("row_count");
            values.add("null_count");
            values.add("min_max");
            values.add("checksum");
            values.add("approx_distinct");
            return values;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HashSpec {
        public Boolean enabled = Boolean.TRUE;
        public String algorithm = "xxh64";
        public Boolean ignoreRowOrder = Boolean.TRUE;
        public Boolean canonicalizeComplexTypes = Boolean.FALSE;
        public String collisionPolicy = "escalate_to_exact";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SegmentSpec {
        public List<String> by = new ArrayList<>();
        public Integer chunkRows = 1_000_000;
        public DigestSpec digest = new DigestSpec();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DigestSpec {
        public Boolean enabled = Boolean.TRUE;
        public String algorithm = "xxh64";
        public Boolean ignoreRowOrder = Boolean.TRUE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiffSpec {
        public Integer maxSamples = 500;
        public Boolean exactWhenSuspect = Boolean.TRUE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DmlSpec {
        public String insert = "completeness";
        public String update = "latest_state";
        public DeleteSpec delete = new DeleteSpec();
        public String merge = "latest_state";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeleteSpec {
        public String mode = "hard_delete";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DdlSpec {
        public String mode = "compatible";
        public Map<String, String> renameMapping = new LinkedHashMap<>();
        public List<TypeRuleSpec> typeRules = new ArrayList<>();
        public String partitionEvolution = "allow";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeRuleSpec {
        public String from;
        public String to;
        public String action;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvidenceSpec {
        public Boolean enabled = Boolean.FALSE;
        public String type = "none";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputSpec {
        public String dir = "./reports/default";
        public List<String> format = defaultFormats();

        private static List<String> defaultFormats() {
            List<String> values = new ArrayList<>();
            values.add("json");
            values.add("html");
            values.add("csv");
            return values;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StateSpec {
        public String backend = "sqlite";
        public String path = "./.recon/state.db";
    }
}

