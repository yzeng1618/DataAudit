package io.github.dataaudit.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.model.HistoricalCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CorpusValidator {
    private final ObjectMapper mapper;

    public CorpusValidator() {
        this(AiObjectMapper.create());
    }

    CorpusValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CorpusValidationReport validateDirectory(Path directory) throws Exception {
        CorpusValidationReport report = new CorpusValidationReport();
        if (directory == null || !Files.isDirectory(directory)) {
            return report;
        }
        Set<String> ids = new LinkedHashSet<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                HistoricalCase historicalCase = mapper.readValue(file.toFile(), HistoricalCase.class);
                List<String> issues = validate(historicalCase);
                if (historicalCase.id != null && !historicalCase.id.isBlank() && !ids.add(historicalCase.id)) {
                    issues = new java.util.ArrayList<>(issues);
                    issues.add("duplicate id: " + historicalCase.id);
                }
                if (issues.isEmpty()) {
                    report.accept(historicalCase.id);
                } else {
                    report.reject(file.getFileName() + ": " + String.join("; ", issues));
                }
            }
        }
        return report;
    }

    public List<String> validate(HistoricalCase historicalCase) {
        List<String> issues = new java.util.ArrayList<>();
        if (historicalCase == null) {
            issues.add("case is empty");
            return issues;
        }
        requireText(issues, "id", historicalCase.id);
        requireText(issues, "title", historicalCase.title);
        requireText(issues, "source_type", historicalCase.sourceType);
        requireText(issues, "target_type", historicalCase.targetType);
        requireList(issues, "symptoms", historicalCase.symptoms);
        requireList(issues, "evidence_patterns", historicalCase.evidencePatterns);
        requireList(issues, "likely_causes", historicalCase.likelyCauses);
        requireList(issues, "recommended_checks", historicalCase.recommendedChecks);
        requireList(issues, "tags", historicalCase.tags);
        return issues;
    }

    public boolean valid(HistoricalCase historicalCase) {
        return validate(historicalCase).isEmpty();
    }

    private void requireText(List<String> issues, String field, String value) {
        if (value == null || value.isBlank()) {
            issues.add("missing " + field);
        }
    }

    private void requireList(List<String> issues, String field, List<String> values) {
        if (values == null || values.isEmpty() || values.stream().allMatch(value -> value == null || value.isBlank())) {
            issues.add("missing " + field);
        }
    }
}
