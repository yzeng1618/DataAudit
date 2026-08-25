// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.rag;

import io.github.dataaudit.ai.model.HistoricalCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptValidProductionCaseFiles() throws Exception {
        CorpusValidationReport report = new CorpusValidator().validateDirectory(Path.of("..", "examples", "ai-copilot", "cases"));

        assertTrue(report.valid());
        assertTrue(report.acceptedCaseIds().containsAll(List.of(
                "oracle-decimal-drift",
                "iceberg-partition-overwrite",
                "flink-cdc-boundary-miss",
                "doris-stream-load-redirect")));
    }

    @Test
    void shouldReportInvalidCaseFilesAndExcludeThemFromProductionRetrieval() throws Exception {
        Files.writeString(tempDir.resolve("invalid.json"), """
                {
                  "title": "Missing stable id and evidence",
                  "symptoms": ["checksum mismatch"],
                  "likely_causes": ["可能是测试数据不完整"],
                  "recommended_checks": ["检查测试数据"],
                  "tags": ["invalid"]
                }
                """);
        Files.writeString(tempDir.resolve("valid.json"), """
                {
                  "id": "valid-custom-case",
                  "title": "Valid custom production case",
                  "source_type": "jdbc",
                  "target_type": "jdbc",
                  "symptoms": ["checksum mismatch"],
                  "evidence_patterns": ["checksum", "amount"],
                  "likely_causes": ["可能是 amount normalization 不一致"],
                  "recommended_checks": ["检查 amount scale"],
                  "tags": ["checksum", "amount"]
                }
                """);

        CorpusValidationReport report = new CorpusValidator().validateDirectory(tempDir);
        LocalCaseRetriever retriever = LocalCaseRetriever.fromDirectory(tempDir);
        List<HistoricalCase> retrieved = retriever.retrieve(Map.of("signal", "invalid amount checksum"), 10);

        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("invalid.json")));
        assertTrue(retrieved.stream().anyMatch(item -> "valid-custom-case".equals(item.id)));
        assertFalse(retrieved.stream().anyMatch(item -> "Missing stable id".equals(item.title)));
    }
}
