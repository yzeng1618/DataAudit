package io.github.dataaudit.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.spi.model.ConfidenceLevel;
import io.github.dataaudit.spi.model.ProofMode;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuditAiCommandTest {
    private final ObjectMapper mapper = AiObjectMapper.create();

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateProfileAndReviewFromTask() throws Exception {
        Path task = writeTask("orders_with_key.yaml", true);
        Path profile = tempDir.resolve("table_profile.json");
        Path review = tempDir.resolve("profile_review.md");

        int exitCode = new CommandLine(new DataAuditMain()).execute(
                "ai", "profile",
                "--task", task.toString(),
                "--output", profile.toString(),
                "--review", review.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(profile));
        assertTrue(Files.exists(review));
        JsonNode root = mapper.readTree(profile.toFile());
        assertEquals("oracle", root.at("/source/type").asText());
        assertEquals("overwrite", root.at("/sync_context/write_mode").asText());
        assertTrue(Files.readString(review).contains("Profile status"));
    }

    @Test
    void shouldStopPlanWhenProfileNeedsReviewUnlessAccepted() throws Exception {
        Path task = writeTask("orders_no_key.yaml", false);
        Path plan = tempDir.resolve("audit_plan.json");

        int reviewExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "plan",
                "--task", task.toString(),
                "--output", plan.toString());
        assertEquals(6, reviewExit);
        assertFalse(Files.exists(plan));

        int acceptedExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "plan",
                "--task", task.toString(),
                "--output", plan.toString(),
                "--accept-profile");
        assertEquals(0, acceptedExit);
        JsonNode root = mapper.readTree(plan.toFile());
        assertTrue(root.at("/semantic_analysis/candidate_primary_keys").toString().contains("order_id"));
        assertFalse(root.at("/deterministic_boundary/ai_consistency_conclusion").asBoolean());
    }

    @Test
    void shouldExplainAndRenderReport() throws Exception {
        Path task = writeTask("orders_with_key.yaml", true);
        Path plan = tempDir.resolve("audit_plan.json");
        Path result = tempDir.resolve("audit_result.json");
        Path analysis = tempDir.resolve("root_cause_analysis.json");
        Path report = tempDir.resolve("audit_report.md");
        new CommandLine(new DataAuditMain()).execute(
                "ai", "plan",
                "--task", task.toString(),
                "--output", plan.toString());
        Files.writeString(result, """
                {
                  "status": "DIFF_FOUND",
                  "source_count": 500000,
                  "target_count": 499800,
                  "diff_partition": "dt=2026-04-24",
                  "logs": "overwrite partition dt=2026-04-24 sink commit success"
                }
                """);

        int explainExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "explain",
                "--plan", plan.toString(),
                "--result", result.toString(),
                "--output", analysis.toString());
        int reportExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "report",
                "--plan", plan.toString(),
                "--result", result.toString(),
                "--analysis", analysis.toString(),
                "--template", "technical",
                "--output", report.toString());

        assertEquals(0, explainExit);
        assertEquals(0, reportExit);
        assertTrue(Files.readString(analysis).contains("possible_root_causes"));
        assertTrue(Files.readString(report).contains("技术排查版"));
    }

    @Test
    void shouldWriteIntegratedAiReportSidecarsFromDeterministicReport() throws Exception {
        TaskFileSpec spec = taskSpec();
        ReportModel report = new ReportModel();
        report.result.status = "DIFF_FOUND";
        report.result.proofMode = ProofMode.EXACT_DIFF;
        report.result.confidence = ConfidenceLevel.EXACT;
        SliceDescriptor slice = new SliceDescriptor();
        slice.sliceKey = "dt=2026-04-24";
        report.result.suspectSlices.add(slice);

        DataAuditMain.AiReportArtifacts artifacts = DataAuditMain.writeAiReportSidecars(
                spec,
                report,
                new DataAuditMain.AiProviderOptions(),
                new DataAuditMain.ProfileOptions(),
                "technical",
                null);

        assertTrue(Files.exists(artifacts.profile));
        assertTrue(Files.exists(artifacts.review));
        assertTrue(Files.exists(artifacts.plan));
        assertTrue(Files.exists(artifacts.analysis));
        assertTrue(Files.exists(artifacts.markdown));
        String markdown = Files.readString(artifacts.markdown);
        assertTrue(markdown.contains("确定性核验发现差异"));
        assertTrue(markdown.contains("EXACT_DIFF"));
        assertTrue(markdown.contains("dt=2026-04-24"));
    }

    @Test
    void shouldWriteAiSidecarArtifactMetadataWithoutMutatingDeterministicReport() throws Exception {
        TaskFileSpec spec = taskSpec();
        ReportModel report = new ReportModel();
        report.runId = "deterministic-run";
        report.result.status = "DIFF_FOUND";
        report.result.rootCause = "value_mismatch";
        report.result.proofMode = ProofMode.EXACT_DIFF;
        report.result.confidence = ConfidenceLevel.EXACT;
        SliceDescriptor slice = new SliceDescriptor();
        slice.sliceKey = "dt=2026-04-24";
        report.result.suspectSlices.add(slice);

        DataAuditMain.AiReportArtifacts artifacts = DataAuditMain.writeAiReportSidecars(
                spec,
                report,
                new DataAuditMain.AiProviderOptions(),
                new DataAuditMain.ProfileOptions(),
                "technical",
                null);

        assertEquals("DIFF_FOUND", report.result.status);
        assertEquals("value_mismatch", report.result.rootCause);
        assertEquals(ProofMode.EXACT_DIFF, report.result.proofMode);
        assertEquals(ConfidenceLevel.EXACT, report.result.confidence);
        assertEquals("dt=2026-04-24", report.result.suspectSlices.get(0).sliceKey);

        JsonNode profile = mapper.readTree(artifacts.profile.toFile());
        JsonNode plan = mapper.readTree(artifacts.plan.toFile());
        JsonNode analysis = mapper.readTree(artifacts.analysis.toFile());
        assertSidecarMetadata(profile, "table_profile", "data-audit-table-profile-v1");
        assertSidecarMetadata(plan, "ai_audit_plan", "data-audit-ai-audit-plan-v1");
        assertSidecarMetadata(analysis, "root_cause_analysis", "data-audit-root-cause-analysis-v1");
    }

    @Test
    void shouldGenerateRepairPlanAndAnswerQuestion() throws Exception {
        Path task = writeTask("orders_with_key.yaml", true);
        Path planFile = tempDir.resolve("audit_plan.json");
        Path resultFile = tempDir.resolve("audit_result.json");
        Path analysisFile = tempDir.resolve("root_cause_analysis.json");
        Path repairFile = tempDir.resolve("repair_plan.json");
        Path patchedTask = tempDir.resolve("patched-task.yaml");
        Path answerFile = tempDir.resolve("answer.json");

        AuditPlan plan = new AuditPlan();
        plan.missingInformation.add("normalize.timezone");
        mapper.writeValue(planFile.toFile(), plan);
        Files.writeString(resultFile, """
                {"status":"DIFF_FOUND","proof_mode":"EXACT_DIFF","diff_partition":"dt=2026-04-24"}
                """);
        RootCauseAnalysis analysis = new RootCauseAnalysis();
        RootCauseAnalysis.PossibleCause cause = new RootCauseAnalysis.PossibleCause();
        cause.hypothesis = "可能是目标端分区 overwrite 覆盖范围不完整";
        cause.confidence = 0.8;
        cause.evidence.add("overwrite");
        cause.recommendedChecks.add("检查目标端 snapshot");
        cause.missingInformation.add("target snapshot files");
        analysis.possibleRootCauses.add(cause);
        analysis.recommendedChecks.add("检查目标端 snapshot");
        mapper.writeValue(analysisFile.toFile(), analysis);

        int repairExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "repair",
                "--plan", planFile.toString(),
                "--result", resultFile.toString(),
                "--analysis", analysisFile.toString(),
                "--output", repairFile.toString(),
                "--task", task.toString(),
                "--patched-task", patchedTask.toString());
        int askExit = new CommandLine(new DataAuditMain()).execute(
                "ai", "ask",
                "--plan", planFile.toString(),
                "--result", resultFile.toString(),
                "--analysis", analysisFile.toString(),
                "--question", "Does this block acceptance?",
                "--output", answerFile.toString());

        assertEquals(0, repairExit);
        assertEquals(0, askExit);
        assertTrue(Files.readString(repairFile).contains("manual_data_fix"));
        assertTrue(Files.readString(patchedTask).contains("write_mode"));
        assertTrue(Files.readString(answerFile).contains("不建议验收通过"));
    }

    private Path writeTask(String fileName, boolean withKey) throws Exception {
        Path path = tempDir.resolve(fileName);
        String keyBlock = withKey ? """
                  key:
                    - order_id
                """ : "";
        Files.writeString(path, """
                task:
                  name: orders
                  mode: post_check
                source:
                  type: oracle
                  table: ods.orders
                target:
                  type: iceberg
                  table: dw.orders
                object:
                %s  partition_by:
                    - dt
                  estimated_rows: 5000000
                  columns:
                    - order_id
                    - user_id
                    - amount
                    - status
                    - create_time
                    - dt
                normalize:
                  timezone: Asia/Shanghai
                semantics:
                  ai:
                    write_mode: overwrite
                output:
                  dir: %s
                """.formatted(keyBlock, tempDir.resolve("reports").toString().replace("\\", "\\\\")));
        return path;
    }

    private TaskFileSpec taskSpec() {
        TaskFileSpec spec = new TaskFileSpec();
        spec.task.name = "orders";
        spec.task.mode = "post_check";
        spec.source.type = "jdbc";
        spec.source.table = "ods.orders";
        spec.target.type = "jdbc";
        spec.target.table = "dw.orders";
        spec.object.key.add("order_id");
        spec.object.partitionBy.add("dt");
        spec.object.estimatedRows = 5_000_000L;
        spec.object.columns.add("order_id");
        spec.object.columns.add("amount");
        spec.object.columns.add("status");
        spec.object.columns.add("dt");
        spec.normalize.timezone = "Asia/Shanghai";
        spec.semantics.ai.writeMode = "overwrite";
        spec.output.dir = tempDir.resolve("integrated-reports").toString();
        return spec;
    }

    private void assertSidecarMetadata(JsonNode root, String artifactType, String schemaVersion) {
        assertEquals("1", root.path("artifact_version").asText());
        assertEquals(artifactType, root.path("artifact_type").asText());
        assertEquals("data-audit-ai", root.path("producer").asText());
        assertEquals(schemaVersion, root.path("schema_version").asText());
        assertFalse(root.path("created_at").asText().isBlank());
    }
}
