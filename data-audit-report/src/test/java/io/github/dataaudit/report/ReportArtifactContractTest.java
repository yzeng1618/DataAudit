package io.github.dataaudit.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReportModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportArtifactContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @TempDir
    Path tempDir;

    @Test
    void writerAddsReportArtifactMetadataWithoutChangingReportShape() throws Exception {
        ReportModel report = new ReportModel();
        report.runId = "run-123";
        report.plan.taskName = "orders_reconcile";
        BoundaryRef boundary = new BoundaryRef();
        boundary.type = "job_finish";
        boundary.reference = "latest";
        boundary.fingerprint = "job_finish:latest";
        report.plan.boundary = boundary;
        report.result.status = "DIFF_FOUND";
        report.result.proofMode = ProofMode.EXACT_DIFF;
        report.result.confidence = ConfidenceLevel.EXACT;

        new JsonHtmlReportWriter().write(report, tempDir);

        JsonNode root = mapper.readTree(tempDir.resolve("report.json").toFile());
        assertEquals("1", root.path("artifact_version").asText());
        assertEquals("report", root.path("artifact_type").asText());
        assertEquals("data-audit-cli", root.path("producer").asText());
        assertEquals("data-audit-report-v1", root.path("schema_version").asText());
        assertEquals("run-123", root.path("run_id").asText());
        assertEquals("orders_reconcile", root.path("task_name").asText());
        assertFalse(root.path("created_at").asText().isBlank());
        assertTrue(root.has("plan"));
        assertTrue(root.has("result"));
        assertTrue(root.has("evidence"));
        assertEquals("DIFF_FOUND", root.path("result").path("status").asText());
    }

    @Test
    void legacyReportWithoutArtifactMetadataStillDeserializes() throws Exception {
        String legacy = """
                {
                  "run_id": "legacy-run",
                  "generated_at": "2026-06-08T00:00:00Z",
                  "plan": {
                    "task_name": "legacy_task",
                    "decision_trace": []
                  },
                  "result": {
                    "status": "CONSISTENT",
                    "proof_mode": "GLOBAL_CHECKSUM",
                    "confidence": "HIGH",
                    "no_key_mode": false,
                    "suspect_slices": [],
                    "sampling_summary": {},
                    "diff": {"consistent": true, "samples": []}
                  },
                  "evidence": {
                    "notes": [],
                    "global_signal": {},
                    "localization": {},
                    "exact_diff": {}
                  }
                }
                """;

        ReportModel report = mapper.readValue(legacy, ReportModel.class);

        assertEquals("legacy-run", report.runId);
        assertEquals("legacy_task", report.plan.taskName);
        assertEquals("CONSISTENT", report.result.status);
        assertEquals("1", report.artifactVersion);
        assertEquals("report", report.artifactType);
        assertEquals("data-audit-cli", report.producer);
        assertEquals("data-audit-report-v1", report.schemaVersion);
    }

    @Test
    void productAcceptanceFixturesExposeArtifactMetadataAndLockedFacts() throws Exception {
        List<String> scenarios = List.of(
                "jdbc-jdbc",
                "jdbc-iceberg",
                "iceberg-jdbc",
                "trino-query-plane"
        );

        for (String scenario : scenarios) {
            Path reportPath = Path.of("..", "examples", "artifact-contracts", scenario, "report.json");
            JsonNode root = mapper.readTree(reportPath.toFile());
            assertEquals("1", root.path("artifact_version").asText(), scenario);
            assertEquals("report", root.path("artifact_type").asText(), scenario);
            assertEquals("data-audit-cli", root.path("producer").asText(), scenario);
            assertFalse(root.path("result").path("status").asText().isBlank(), scenario);
            assertFalse(root.path("result").path("proof_mode").asText().isBlank(), scenario);
            assertFalse(root.path("result").path("confidence").asText().isBlank(), scenario);
            assertTrue(root.path("result").has("suspect_slices"), scenario);
            assertTrue(root.path("result").has("diff"), scenario);

            ReportModel report = mapper.treeToValue(root, ReportModel.class);
            assertEquals(root.path("result").path("status").asText(), report.result.status, scenario);
        }
    }
}
