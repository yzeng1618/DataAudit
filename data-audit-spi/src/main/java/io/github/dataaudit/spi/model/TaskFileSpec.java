package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskFileSpec {
    public TaskSpec task = new TaskSpec();
    public BoundarySpec boundary = new BoundarySpec();
    public PlannerSpec planner = new PlannerSpec();
    public QueryConnectorSpec queryConnector;
    public EndpointSpec source = new EndpointSpec();
    public EndpointSpec target = new EndpointSpec();
    public ObjectSpec object = new ObjectSpec();
    public NormalizeSpec normalize = new NormalizeSpec();
    public SemanticsSpec semantics = new SemanticsSpec();
    public ResourceSpec resources = new ResourceSpec();
    public OutputSpec output = new OutputSpec();

    public static class TaskSpec {
        public String name;
        public String description;
        public String mode = "post_check";
    }

    public static class BoundarySpec {
        public String type = "job_finish";
        public String reference = "latest";
        public String gracePeriod;
    }

    public static class QueryConnectorSpec {
        public String type = "trino";
        public String uri;
        public String user;
        public String password;
        public String catalog;
        public String schema;
        public Map<String, String> sessionProperties = new LinkedHashMap<>();
    }

    public static class EndpointSpec {
        public String type;
        public String catalog;
        public String schema;
        public String table;
        public String query;

        public String url;
        public String username;
        public String password;
        public Map<String, Object> options = new LinkedHashMap<>();

        public String catalogType;
        public String warehouse;
        public String uri;
        public String namespace;
        public String snapshotId;
        public String location;
    }

    public static class ObjectSpec {
        public List<String> key = new ArrayList<>();
        public List<String> columns = new ArrayList<>();
        public List<String> partitionBy = new ArrayList<>();
        public List<String> groupBy = new ArrayList<>();
        public Long estimatedRows;
        public Long estimatedBytes;
        public String routingStrategy;
    }

    public static class PlannerSpec {
        public String scaleOverride;
    }

    public static class ResourceSpec {
        public Long maxInMemoryRows = 100_000L;
        public Integer maxDiffSamples = 500;
        public Long globalTimeoutMillis = 0L;
        public Long queryTimeoutMillis = 0L;
        public Integer segmentParallelism = 1;
    }

    public static class NormalizeSpec {
        public String timezone = "UTC";
        public Boolean trimString = Boolean.FALSE;
        public Boolean emptyAsNull = Boolean.FALSE;
        public List<String> caseInsensitiveColumns = new ArrayList<>();
        public Map<String, Integer> decimalScale = new LinkedHashMap<>();
    }

    public static class SemanticsSpec {
        public DmlSpec dml = new DmlSpec();
        public DdlSpec ddl = new DdlSpec();
        public AiSpec ai = new AiSpec();
    }

    public static class DmlSpec {
        public String insert = "completeness";
        public String update = "latest_state";
        public DeleteSpec delete = new DeleteSpec();
        public String merge = "latest_state";
    }

    public static class DeleteSpec {
        public String mode = "hard_delete";
    }

    public static class DdlSpec {
        public String mode = "compatible";
        public Map<String, String> renameMapping = new LinkedHashMap<>();
        public List<TypeRuleSpec> typeRules = new ArrayList<>();
        public String partitionEvolution = "allow";
    }

    public static class AiSpec {
        public String syncMode;
        public String writeMode;
        public List<String> metricFields = new ArrayList<>();
        public List<String> enumFields = new ArrayList<>();
        public List<String> timeFields = new ArrayList<>();
        public Map<String, String> attributes = new LinkedHashMap<>();
    }

    public static class TypeRuleSpec {
        public String from;
        public String to;
        public String action;
    }

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
}
