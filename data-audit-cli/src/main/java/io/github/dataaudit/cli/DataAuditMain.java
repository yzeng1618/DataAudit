package io.github.dataaudit.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dataaudit.ai.AiObjectMapper;
import io.github.dataaudit.ai.model.AuditPlan;
import io.github.dataaudit.ai.model.CopilotAnswer;
import io.github.dataaudit.ai.model.ProfileReview;
import io.github.dataaudit.ai.model.RepairPlan;
import io.github.dataaudit.ai.model.RootCauseAnalysis;
import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.ai.profile.ProfileCollector;
import io.github.dataaudit.ai.profile.ProfileCollectionOptions;
import io.github.dataaudit.ai.profile.ProfileQualityGate;
import io.github.dataaudit.ai.profile.ProfileReviewMarkdownWriter;
import io.github.dataaudit.ai.rag.HashingEmbeddingClient;
import io.github.dataaudit.ai.rag.HybridCaseRetriever;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.rag.RagRetriever;
import io.github.dataaudit.ai.rag.VectorCaseRetriever;
import io.github.dataaudit.ai.repair.RepairPlanner;
import io.github.dataaudit.ai.qa.CopilotQaService;
import io.github.dataaudit.ai.workflow.AiWorkflowConfig;
import io.github.dataaudit.ai.workflow.PlanningOrchestrator;
import io.github.dataaudit.ai.workflow.ReportOrchestrator;
import io.github.dataaudit.ai.workflow.RootCauseOrchestrator;
import io.github.dataaudit.core.BoundaryResolver;
import io.github.dataaudit.core.ConnectorRegistry;
import io.github.dataaudit.core.DdlAuditor;
import io.github.dataaudit.core.DiffEngine;
import io.github.dataaudit.core.DmlAuditor;
import io.github.dataaudit.core.ExecutionService;
import io.github.dataaudit.core.HashProvider;
import io.github.dataaudit.core.NormalizationService;
import io.github.dataaudit.core.PlanningService;
import io.github.dataaudit.core.SchemaEngine;
import io.github.dataaudit.core.SegmentEngine;
import io.github.dataaudit.core.SpecValidator;
import io.github.dataaudit.core.SummaryEngine;
import io.github.dataaudit.report.JsonHtmlReportWriter;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.TaskFileSpec;
import io.github.dataaudit.state.sqlite.SqliteStateStore;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "data-audit",
        mixinStandardHelpOptions = true,
        description = "Task-post consistency audit CLI.",
        subcommands = {
                DataAuditMain.PlanCommand.class,
                DataAuditMain.CheckCommand.class,
                DataAuditMain.DiffCommand.class,
                DataAuditMain.ReportCommand.class,
                DataAuditMain.AiCommand.class
        }
)
public class DataAuditMain implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new DataAuditMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "plan", description = "Generate execution plan only.")
    static class PlanCommand implements Callable<Integer> {
        @Option(names = {"-f", "--file"}, required = true, description = "task yaml path")
        private Path taskFile;

        @Override
        public Integer call() throws Exception {
            TaskFileSpec spec = loadSpec(taskFile);
            ExecutionService service = newExecutionService(spec);
            ExecutionPlan plan = service.plan(spec);
            System.out.println(objectMapper().writeValueAsString(plan));
            return plan.refuseReason == null ? 0 : 5;
        }
    }

    @Command(name = "check", description = "Run standard reconciliation.")
    static class CheckCommand implements Callable<Integer> {
        @Option(names = {"-f", "--file"}, required = true, description = "task yaml path")
        private Path taskFile;

        @Option(names = "--ai-report", description = "generate AI Copilot report sidecars after deterministic check")
        private boolean aiReport;

        @Option(names = "--ai-report-template", defaultValue = "technical",
                description = "technical, acceptance, or management")
        private String aiReportTemplate;

        @Option(names = "--ai-report-output", description = "AI Markdown report output path")
        private Path aiReportOutput;

        @CommandLine.Mixin
        private AiProviderOptions aiProviderOptions = new AiProviderOptions();

        @CommandLine.Mixin
        private ProfileOptions profileOptions = new ProfileOptions();

        @Override
        public Integer call() throws Exception {
            TaskFileSpec spec = loadSpec(taskFile);
            ExecutionService service = newExecutionService(spec);
            ReportModel report = service.check(spec);
            if (aiReport) {
                try {
                    AiReportArtifacts artifacts = writeAiReportSidecars(
                            spec,
                            report,
                            aiProviderOptions,
                            profileOptions,
                            aiReportTemplate,
                            aiReportOutput);
                    System.out.println("AI audit plan written: " + artifacts.plan);
                    System.out.println("AI root cause analysis written: " + artifacts.analysis);
                    System.out.println("AI report written: " + artifacts.markdown);
                } catch (Exception e) {
                    System.err.println("AI report generation failed; deterministic check result is unchanged: "
                            + e.getMessage());
                }
            }
            printSummary(report);
            return exitCode(report.result.status);
        }
    }

    @Command(name = "diff", description = "Run exact diff on a specified slice.")
    static class DiffCommand implements Callable<Integer> {
        @Option(names = {"-f", "--file"}, required = true, description = "task yaml path")
        private Path taskFile;

        @Option(names = {"--slice"}, required = true, description = "slice key, for example dt=2026-03-10")
        private String slice;

        @Override
        public Integer call() throws Exception {
            TaskFileSpec spec = loadSpec(taskFile);
            ExecutionService service = newExecutionService(spec);
            ReportModel report = service.diff(spec, slice);
            printSummary(report);
            return exitCode(report.result.status);
        }
    }

    @Command(name = "report", description = "Report helpers.", subcommands = {ShowCommand.class})
    static class ReportCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(
            name = "ai",
            mixinStandardHelpOptions = true,
            description = "AI Copilot Alpha helpers.",
            subcommands = {
                    AiProfileCommand.class,
                    AiPlanCommand.class,
                    AiExplainCommand.class,
                    AiReportCommand.class,
                    AiRepairCommand.class,
                    AiAskCommand.class
            }
    )
    static class AiCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    static class AiProviderOptions {
        @Option(names = "--ai-provider", description = "disabled, mock, http-json, openai-compatible, or openai-sdk")
        String aiProvider = env("DATAAUDIT_AI_PROVIDER", "disabled");

        @Option(names = "--ai-endpoint", description = "HTTP JSON or OpenAI-compatible AI provider endpoint")
        String aiEndpoint = env("DATAAUDIT_AI_ENDPOINT", null);

        @Option(names = "--ai-api-key", description = "AI provider API key")
        String aiApiKey = env("DATAAUDIT_AI_API_KEY", null);

        @Option(names = "--ai-model", description = "AI model id for OpenAI-compatible providers")
        String aiModel = env("DATAAUDIT_AI_MODEL", "mimo-v2.5-pro");

        @Option(names = "--rag-corpus", description = "local historical case corpus directory")
        Path ragCorpus = envPath("DATAAUDIT_AI_RAG_CORPUS");

        @Option(names = "--rag-mode", description = "lexical, vector, or hybrid")
        String ragMode = env("DATAAUDIT_AI_RAG_MODE", "hybrid");
    }

    static class ProfileOptions {
        @Option(names = "--profile-max-sample-rows", description = "max rows for bounded profile sample collection")
        int maxSampleRows = envInt("DATAAUDIT_PROFILE_MAX_SAMPLE_ROWS", 20);

        @Option(names = "--profile-max-sample-fields", description = "max fields for bounded profile sample collection")
        int maxSampleFields = envInt("DATAAUDIT_PROFILE_MAX_SAMPLE_FIELDS", 20);

        @Option(names = "--profile-timeout-ms", description = "profile sample collection timeout in milliseconds")
        long timeoutMillis = envLong("DATAAUDIT_PROFILE_TIMEOUT_MS", 3000L);
    }

    @Command(name = "profile", mixinStandardHelpOptions = true, description = "Build table_profile.json from task.yaml.")
    static class AiProfileCommand implements Callable<Integer> {
        @Option(names = "--task", required = true, description = "task yaml path")
        private Path taskFile;

        @Option(names = "--output", required = true, description = "table_profile.json output path")
        private Path output;

        @Option(names = "--review", description = "profile_review.md output path")
        private Path review;

        @CommandLine.Mixin
        private ProfileOptions profileOptions = new ProfileOptions();

        @Override
        public Integer call() throws Exception {
            TaskFileSpec spec = loadSpec(taskFile);
            TableProfile profile = collectProfile(spec, profileOptions(profileOptions));
            ProfileReview profileReview = new ProfileQualityGate().evaluate(profile);
            writeJson(output, profile);
            Path reviewPath = review == null ? sibling(output, "profile_review.md") : review;
            writeText(reviewPath, new ProfileReviewMarkdownWriter().render(profileReview));
            printProfileSummary(profileReview, reviewPath);
            return profileReview.status == ProfileReview.Status.INSUFFICIENT ? 2 : 0;
        }
    }

    @Command(name = "plan", mixinStandardHelpOptions = true, description = "Generate AI audit_plan.json.")
    static class AiPlanCommand implements Callable<Integer> {
        @Option(names = "--task", description = "task yaml path; default product path")
        private Path taskFile;

        @Option(names = "--profile", description = "advanced/test table_profile.json path")
        private Path profileFile;

        @Option(names = "--output", required = true, description = "audit_plan.json output path")
        private Path output;

        @Option(names = "--review", description = "profile_review.md output path when review is required")
        private Path review;

        @Option(names = "--accept-profile", description = "continue when profile status is REVIEW_REQUIRED")
        private boolean acceptProfile;

        @CommandLine.Mixin
        private AiProviderOptions aiProviderOptions = new AiProviderOptions();

        @CommandLine.Mixin
        private ProfileOptions profileOptions = new ProfileOptions();

        @Override
        public Integer call() throws Exception {
            if ((taskFile == null && profileFile == null) || (taskFile != null && profileFile != null)) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Specify exactly one of --task or --profile");
            }
            TableProfile profile;
            if (profileFile != null) {
                profile = aiMapper().readValue(profileFile.toFile(), TableProfile.class);
            } else {
                TaskFileSpec spec = loadSpec(taskFile);
                profile = collectProfile(spec, profileOptions(profileOptions));
                ProfileReview profileReview = new ProfileQualityGate().evaluate(profile);
                if (profileReview.status == ProfileReview.Status.INSUFFICIENT) {
                    Path profilePath = sibling(output, "table_profile.json");
                    writeJson(profilePath, profile);
                    Path reviewPath = review == null ? sibling(output, "profile_review.md") : review;
                    writeText(reviewPath, new ProfileReviewMarkdownWriter().render(profileReview));
                    printProfileSummary(profileReview, reviewPath);
                    return 2;
                }
                if (profileReview.status == ProfileReview.Status.REVIEW_REQUIRED && !acceptProfile) {
                    Path profilePath = sibling(output, "table_profile.json");
                    writeJson(profilePath, profile);
                    Path reviewPath = review == null ? sibling(output, "profile_review.md") : review;
                    writeText(reviewPath, new ProfileReviewMarkdownWriter().render(profileReview));
                    printProfileSummary(profileReview, reviewPath);
                    System.out.println("Audit plan not generated yet.");
                    return 6;
                }
            }
            AuditPlan plan = new PlanningOrchestrator(
                    aiConfig(aiProviderOptions),
                    ragRetriever(aiProviderOptions.ragCorpus, aiProviderOptions.ragMode)).plan(profile);
            writeJson(output, plan);
            System.out.println("Profile status: CONFIRMED");
            System.out.println("Audit plan written: " + output);
            return 0;
        }
    }

    @Command(name = "explain", mixinStandardHelpOptions = true, description = "Generate root_cause_analysis.json from plan and result.")
    static class AiExplainCommand implements Callable<Integer> {
        @Option(names = "--plan", required = true, description = "audit_plan.json path")
        private Path planFile;

        @Option(names = "--result", required = true, description = "audit_result.json or report.json path")
        private Path resultFile;

        @Option(names = "--output", required = true, description = "root_cause_analysis.json output path")
        private Path output;

        @CommandLine.Mixin
        private AiProviderOptions aiProviderOptions = new AiProviderOptions();

        @Override
        public Integer call() throws Exception {
            AuditPlan plan = aiMapper().readValue(planFile.toFile(), AuditPlan.class);
            Map<String, Object> result = readJsonMap(resultFile);
            RootCauseAnalysis analysis = new RootCauseOrchestrator(
                    aiConfig(aiProviderOptions),
                    ragRetriever(aiProviderOptions.ragCorpus, aiProviderOptions.ragMode))
                    .analyze(plan, result);
            writeJson(output, analysis);
            System.out.println("Root cause analysis written: " + output);
            return 0;
        }
    }

    @Command(name = "report", mixinStandardHelpOptions = true, description = "Generate multi-role Markdown report.")
    static class AiReportCommand implements Callable<Integer> {
        @Option(names = "--plan", required = true, description = "audit_plan.json path")
        private Path planFile;

        @Option(names = "--result", required = true, description = "audit_result.json or report.json path")
        private Path resultFile;

        @Option(names = "--analysis", required = true, description = "root_cause_analysis.json path")
        private Path analysisFile;

        @Option(names = "--template", defaultValue = "technical", description = "technical, acceptance, or management")
        private String template;

        @Option(names = "--output", required = true, description = "Markdown report output path")
        private Path output;

        @CommandLine.Mixin
        private AiProviderOptions aiProviderOptions = new AiProviderOptions();

        @Override
        public Integer call() throws Exception {
            AuditPlan plan = aiMapper().readValue(planFile.toFile(), AuditPlan.class);
            Map<String, Object> result = readJsonMap(resultFile);
            RootCauseAnalysis analysis = aiMapper().readValue(analysisFile.toFile(), RootCauseAnalysis.class);
            String markdown = new ReportOrchestrator(aiConfig(aiProviderOptions))
                    .render(plan, result, analysis, template);
            writeText(output, markdown);
            System.out.println("AI report written: " + output);
            return 0;
        }
    }

    @Command(name = "repair", mixinStandardHelpOptions = true, description = "Generate a safe repair_plan.json.")
    static class AiRepairCommand implements Callable<Integer> {
        @Option(names = "--plan", required = true, description = "audit_plan.json path")
        private Path planFile;

        @Option(names = "--result", required = true, description = "audit_result.json or report.json path")
        private Path resultFile;

        @Option(names = "--analysis", required = true, description = "root_cause_analysis.json path")
        private Path analysisFile;

        @Option(names = "--output", required = true, description = "repair_plan.json output path")
        private Path output;

        @Option(names = "--task", description = "task yaml path, required when --patched-task is used")
        private Path taskFile;

        @Option(names = "--patched-task", description = "write a patched task YAML copy for safe config repairs")
        private Path patchedTask;

        @Override
        public Integer call() throws Exception {
            if (patchedTask != null && taskFile == null) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "--task is required when --patched-task is used");
            }
            AuditPlan plan = aiMapper().readValue(planFile.toFile(), AuditPlan.class);
            Map<String, Object> result = readJsonMap(resultFile);
            RootCauseAnalysis analysis = aiMapper().readValue(analysisFile.toFile(), RootCauseAnalysis.class);
            RepairPlan repairPlan = new RepairPlanner().plan(plan, result, analysis);
            writeJson(output, repairPlan);
            if (patchedTask != null) {
                TaskFileSpec spec = loadSpec(taskFile);
                applySafeTaskPatch(spec, repairPlan);
                writeYaml(patchedTask, spec);
                System.out.println("Patched task YAML written: " + patchedTask);
            }
            System.out.println("Repair plan written: " + output);
            return 0;
        }
    }

    @Command(name = "ask", mixinStandardHelpOptions = true, description = "Ask a single grounded Copilot question.")
    static class AiAskCommand implements Callable<Integer> {
        @Option(names = "--plan", required = true, description = "audit_plan.json path")
        private Path planFile;

        @Option(names = "--result", required = true, description = "audit_result.json or report.json path")
        private Path resultFile;

        @Option(names = "--analysis", required = true, description = "root_cause_analysis.json path")
        private Path analysisFile;

        @Option(names = "--question", required = true, description = "question to answer")
        private String question;

        @Option(names = "--output", description = "optional answer JSON output path")
        private Path output;

        @Override
        public Integer call() throws Exception {
            AuditPlan plan = aiMapper().readValue(planFile.toFile(), AuditPlan.class);
            Map<String, Object> result = readJsonMap(resultFile);
            RootCauseAnalysis analysis = aiMapper().readValue(analysisFile.toFile(), RootCauseAnalysis.class);
            CopilotAnswer answer = new CopilotQaService().answer(plan, result, analysis, question);
            if (output != null) {
                writeJson(output, answer);
            }
            System.out.println(answer.answer);
            return 0;
        }
    }

    @Command(name = "show", description = "Show summary from report.json")
    static class ShowCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "report json path")
        private Path reportFile;

        @Override
        public Integer call() throws Exception {
            ReportModel report = objectMapper().readValue(reportFile.toFile(), ReportModel.class);
            printSummary(report);
            return exitCode(report.result.status);
        }
    }

    private static ExecutionService newExecutionService(TaskFileSpec spec) {
        NormalizationService normalizationService = new NormalizationService();
        SummaryEngine summaryEngine = new SummaryEngine(normalizationService, new HashProvider());
        Path statePath = Paths.get(spec.output.dir).resolve("state.db");
        return new ExecutionService(
                ConnectorRegistry.load(),
                new SqliteStateStore(statePath),
                new JsonHtmlReportWriter(),
                new SpecValidator(),
                new BoundaryResolver(),
                new PlanningService(),
                new SchemaEngine(),
                summaryEngine,
                new SegmentEngine(summaryEngine),
                new DiffEngine(normalizationService),
                new DmlAuditor(),
                new DdlAuditor()
        );
    }

    private static TaskFileSpec loadSpec(Path taskFile) throws Exception {
        try (InputStream inputStream = Files.newInputStream(taskFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(inputStream);
            return objectMapper().convertValue(raw, TaskFileSpec.class);
        }
    }

    static AiReportArtifacts writeAiReportSidecars(TaskFileSpec spec,
                                                   ReportModel report,
                                                   AiProviderOptions aiProviderOptions,
                                                   ProfileOptions profileOptions,
                                                   String template,
                                                   Path markdownOutput) throws Exception {
        Path outputDir = Paths.get(spec.output.dir);
        Files.createDirectories(outputDir);
        Path profilePath = outputDir.resolve("table_profile.json");
        Path reviewPath = outputDir.resolve("profile_review.md");
        Path planPath = outputDir.resolve("ai_audit_plan.json");
        Path analysisPath = outputDir.resolve("root_cause_analysis.json");
        String safeTemplate = safeFileToken(template == null ? "technical" : template);
        Path markdownPath = markdownOutput == null
                ? outputDir.resolve("ai_audit_report_" + safeTemplate + ".md")
                : markdownOutput;

        TableProfile profile = collectProfile(spec, profileOptions(profileOptions));
        ProfileReview profileReview = new ProfileQualityGate().evaluate(profile);
        writeJson(profilePath, profile);
        writeText(reviewPath, new ProfileReviewMarkdownWriter().render(profileReview));
        if (profileReview.status == ProfileReview.Status.INSUFFICIENT) {
            throw new IllegalStateException("profile insufficient for AI report; review file: " + reviewPath);
        }

        AiWorkflowConfig config = aiConfig(aiProviderOptions);
        RagRetriever retriever = ragRetriever(aiProviderOptions.ragCorpus, aiProviderOptions.ragMode);
        AuditPlan plan = new PlanningOrchestrator(config, retriever).plan(profile);
        writeJson(planPath, plan);

        Map<String, Object> result = aiMapper().convertValue(report, new TypeReference<Map<String, Object>>() {
        });
        RootCauseAnalysis analysis = new RootCauseOrchestrator(config, retriever).analyze(plan, result);
        writeJson(analysisPath, analysis);

        String markdown = new ReportOrchestrator(config).render(plan, result, analysis, template);
        writeText(markdownPath, markdown);
        return new AiReportArtifacts(profilePath, reviewPath, planPath, analysisPath, markdownPath);
    }

    private static TableProfile collectProfile(TaskFileSpec spec) {
        return collectProfile(spec, new ProfileCollectionOptions());
    }

    private static TableProfile collectProfile(TaskFileSpec spec, ProfileCollectionOptions options) {
        ConnectorRegistry registry = ConnectorRegistry.load();
        return new ProfileCollector(options, new io.github.dataaudit.ai.profile.ProfileEvidenceEnricher(),
                new io.github.dataaudit.ai.profile.SampleMasker()).collect(spec, registry::open);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private static ObjectMapper aiMapper() {
        return AiObjectMapper.create();
    }

    private static AiWorkflowConfig aiConfig(AiProviderOptions options) {
        URI endpointUri = options.aiEndpoint == null || options.aiEndpoint.isBlank() ? null : URI.create(options.aiEndpoint);
        return new AiWorkflowConfig(options.aiProvider, endpointUri, options.aiApiKey, options.aiModel);
    }

    private static ProfileCollectionOptions profileOptions(ProfileOptions options) {
        ProfileCollectionOptions collectionOptions = new ProfileCollectionOptions();
        collectionOptions.maxSampleRows = options.maxSampleRows;
        collectionOptions.maxSampleFields = options.maxSampleFields;
        collectionOptions.timeoutMillis = options.timeoutMillis;
        return collectionOptions;
    }

    private static RagRetriever ragRetriever(Path corpus) throws Exception {
        return ragRetriever(corpus, "hybrid");
    }

    private static RagRetriever ragRetriever(Path corpus, String mode) throws Exception {
        Path path = corpus == null ? defaultRagCorpus() : corpus;
        String normalizedMode = mode == null ? "hybrid" : mode.toLowerCase(Locale.ROOT);
        return switch (normalizedMode) {
            case "lexical" -> LocalCaseRetriever.fromDirectory(path);
            case "vector" -> VectorCaseRetriever.fromDirectory(path, new HashingEmbeddingClient());
            case "hybrid" -> HybridCaseRetriever.fromDirectory(path, new HashingEmbeddingClient());
            default -> throw new IllegalArgumentException("Unsupported RAG mode: " + mode);
        };
    }

    private static Path defaultRagCorpus() {
        Path examples = Paths.get("examples", "ai-copilot", "cases");
        return Files.isDirectory(examples) ? examples : null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envInt(String name, int fallback) {
        String value = env(name, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long envLong(String name, long fallback) {
        String value = env(name, null);
        return value == null ? fallback : Long.parseLong(value);
    }

    private static Path envPath(String name) {
        String value = env(name, null);
        return value == null ? null : Paths.get(value);
    }

    private static String safeFileToken(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return normalized.isBlank() ? "technical" : normalized;
    }

    static class AiReportArtifacts {
        final Path profile;
        final Path review;
        final Path plan;
        final Path analysis;
        final Path markdown;

        AiReportArtifacts(Path profile, Path review, Path plan, Path analysis, Path markdown) {
            this.profile = profile;
            this.review = review;
            this.plan = plan;
            this.analysis = analysis;
            this.markdown = markdown;
        }
    }

    private static Map<String, Object> readJsonMap(Path file) throws Exception {
        return aiMapper().readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
        });
    }

    private static void writeJson(Path file, Object value) throws Exception {
        ensureParent(file);
        aiMapper().writeValue(file.toFile(), value);
    }

    private static void writeText(Path file, String value) throws Exception {
        ensureParent(file);
        Files.writeString(file, value);
    }

    private static void writeYaml(Path file, TaskFileSpec spec) throws Exception {
        ensureParent(file);
        Map<String, Object> values = objectMapper().convertValue(spec, new TypeReference<Map<String, Object>>() {
        });
        Files.writeString(file, new Yaml().dump(values));
    }

    private static void applySafeTaskPatch(TaskFileSpec spec, RepairPlan repairPlan) {
        for (RepairPlan.RepairAction action : repairPlan.actions) {
            if (!action.autoExecutable || action.configPath == null || action.suggestedValue == null) {
                continue;
            }
            switch (action.configPath) {
                case "normalize.timezone" -> spec.normalize.timezone = action.suggestedValue;
                case "semantics.ai.write_mode" -> spec.semantics.ai.writeMode = action.suggestedValue;
                case "semantics.ai.sync_mode" -> spec.semantics.ai.syncMode = action.suggestedValue;
                default -> {
                    // Unknown config paths are intentionally ignored by the safe patcher.
                }
            }
        }
    }

    private static void ensureParent(Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static Path sibling(Path output, String fileName) {
        Path parent = output.toAbsolutePath().getParent();
        return parent == null ? Paths.get(fileName) : parent.resolve(fileName);
    }

    private static void printProfileSummary(ProfileReview review, Path reviewPath) {
        System.out.println("Profile status: " + review.status);
        if (!review.confirmationItems.isEmpty()) {
            System.out.println("Need confirmation:");
            for (ProfileReview.ConfirmationItem item : review.confirmationItems) {
                System.out.println("- " + item.field + ": " + item.suggestedValue
                        + ", confidence=" + String.format("%.2f", item.confidence));
            }
        }
        if (!review.missingInformation.isEmpty()) {
            System.out.println("Missing information: " + String.join(", ", review.missingInformation));
        }
        System.out.println("Review file: " + reviewPath);
        if (review.status == ProfileReview.Status.REVIEW_REQUIRED) {
            System.out.println("Next:");
            System.out.println("  1. Update task.yaml with overrides, then rerun");
            System.out.println("  2. Or rerun with --accept-profile to continue");
        }
    }

    private static void printSummary(ReportModel report) throws Exception {
        System.out.println("runId=" + report.runId);
        System.out.println("status=" + report.result.status);
        System.out.println("scaleClass=" + report.plan.scaleClass);
        System.out.println("signalStrategy=" + report.plan.signalStrategy);
        System.out.println("localizationStrategy=" + report.plan.localizationStrategy);
        System.out.println("rootCause=" + report.result.rootCause);
        System.out.println("proofMode=" + report.result.proofMode);
        System.out.println("confidence=" + report.result.confidence);
        System.out.println("noKeyMode=" + report.result.noKeyMode);
        System.out.println("fallbackReason=" + report.result.fallbackReason);
        System.out.println("suspectSlices=" + objectMapper().writeValueAsString(report.result.suspectSlices));
        System.out.println("resumeHint=" + report.result.resumeHint);
        System.out.println("planDecisionTrace=" + objectMapper().writeValueAsString(report.plan.decisionTrace));
    }

    private static int exitCode(String status) {
        if ("CONSISTENT".equalsIgnoreCase(status)) {
            return 0;
        }
        if ("DIFF_FOUND".equalsIgnoreCase(status)) {
            return 1;
        }
        if ("UNSTABLE_BOUNDARY".equalsIgnoreCase(status)) {
            return 5;
        }
        if ("EXECUTION_FAILED".equalsIgnoreCase(status)) {
            return 4;
        }
        return 2;
    }
}
