package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.MetadataSnapshot;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.List;
import java.util.Map;

public class DdlAuditor {
    public ReportModel.DdlAuditSection audit(TaskFileSpec spec,
                                             List<String> schemaIssues,
                                             MetadataSnapshot sourceMetadata,
                                             MetadataSnapshot targetMetadata) {
        ReportModel.DdlAuditSection section = new ReportModel.DdlAuditSection();
        section.mode = spec.semantics.ddl.mode;
        section.partitionEvolution = spec.semantics.ddl.partitionEvolution;
        section.renameMapping.putAll(spec.semantics.ddl.renameMapping);
        for (TaskFileSpec.TypeRuleSpec rule : spec.semantics.ddl.typeRules) {
            section.typeRules.add(rule.from + "->" + rule.to + ":" + rule.action);
        }

        section.decisionTrace.add("ddl mode: " + section.mode);
        if (!section.renameMapping.isEmpty()) {
            section.decisionTrace.add("rename mapping applied: " + section.renameMapping);
        }
        if (!section.typeRules.isEmpty()) {
            section.decisionTrace.add("type compatibility rules: " + section.typeRules);
        }
        section.decisionTrace.add("partition evolution policy: " + section.partitionEvolution);

        String sourcePartitionSpec = attribute(sourceMetadata, "partitionSpec");
        String targetPartitionSpec = attribute(targetMetadata, "partitionSpec");
        if (sourcePartitionSpec != null || targetPartitionSpec != null) {
            if (safeEquals(sourcePartitionSpec, targetPartitionSpec)) {
                section.decisionTrace.add("partition spec is aligned across endpoints");
            } else if ("allow".equalsIgnoreCase(section.partitionEvolution)) {
                section.decisionTrace.add("partition evolution detected but tolerated by policy");
            } else {
                section.decisionTrace.add("partition evolution differs across endpoints");
            }
        }

        if (schemaIssues != null && !schemaIssues.isEmpty()) {
            section.verdict = "incompatible";
            section.decisionTrace.add("schema issues: " + schemaIssues);
        } else if ("strict".equalsIgnoreCase(section.mode)) {
            section.verdict = "strict_match";
            section.decisionTrace.add("strict mode satisfied with no schema issues");
        } else {
            section.verdict = "compatible";
            section.decisionTrace.add("schema is compatible under configured DDL policy");
        }
        return section;
    }

    public String classify(List<String> schemaIssues) {
        return schemaIssues != null && !schemaIssues.isEmpty() ? "schema_mismatch" : "consistent";
    }

    private String attribute(MetadataSnapshot snapshot, String key) {
        return snapshot == null || snapshot.attributes == null ? null : snapshot.attributes.get(key);
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
