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
import io.github.dataaudit.ai.rag.EmbeddingClient;
import io.github.dataaudit.ai.rag.EmbeddingClientFactory;
import io.github.dataaudit.ai.rag.EmbeddingProviderConfig;
import io.github.dataaudit.ai.rag.HybridCaseRetriever;
import io.github.dataaudit.ai.rag.LocalCaseRetriever;
import io.github.dataaudit.ai.rag.RagRetriever;
import io.github.dataaudit.ai.rag.VectorStore;
import io.github.dataaudit.ai.rag.VectorStoreCaseRetriever;
import io.github.dataaudit.ai.rag.VectorStoreFactory;
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
import io.github.dataaudit.cli.config.ConfigCheckResult;
import io.github.dataaudit.cli.config.TaskConfigService;
import io.github.dataaudit.cli.doctor.DoctorService;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@Command(
        name = "data-audit",
        mixinStandardHelpOptions = true,
        description = "Task-post consistency audit CLI.",
        subcommands = {
                DataAuditMain.PlanCommand.class,
                DataAuditMain.CheckCommand.class,
                DataAuditMain.DiffCommand.class,
                DataAuditMain.ReportCommand.class,
                DataAuditMain.AiCommand.class,
                DataAuditMain.ConfigCommand.class,
                DataAuditMain.DoctorCommand.class,
                DataAuditMain.VersionCommand.class
        }
)
public class DataAuditMain implements Runnable {
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    public static void main(String[] args) {
        int exitCode = createCommandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * Builds the CLI with production error semantics: an unexpected exception maps to
     * exit code 4 (execution failure) instead of picocli's default 1, which schedulers
     * would misread as "diff found".
     */
    public static CommandLine createCommandLine() {
        return new CommandLine(new DataAuditMain())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("Unexpected error: " + ex);
                    ex.printStackTrace(cmd.getErr());
                    return 4;
                });
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
            TaskFileSpec spec = loadExecutionSpec(taskFile);
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
            TaskFileSpec spec = loadExecutionSpec(taskFile);
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
            TaskFileSpec spec = loadExecutionSpec(taskFile);
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

        @Option(names = "--rag-embedding-provider", description = "local-hashing or http-json")
        String ragEmbeddingProvider = env("DATAAUDIT_AI_RAG_EMBEDDING_PROVIDER", "local-hashing");

        @Option(names = "--rag-embedding-endpoint", description = "HTTP JSON embedding provider endpoint")
        String ragEmbeddingEndpoint = env("DATAAUDIT_AI_RAG_EMBEDDING_ENDPOINT", null);

        @Option(names = "--rag-embedding-api-key", description = "embedding provider API key")
        String ragEmbeddingApiKey = env("DATAAUDIT_AI_RAG_EMBEDDING_API_KEY", null);

        @Option(names = "--rag-embedding-model", description = "embedding provider model")
        String ragEmbeddingModel = env("DATAAUDIT_AI_RAG_EMBEDDING_MODEL", null);

        @Option(names = "--rag-embedding-fail-fast", description = "fail instead of falling back to local hashing when external embedding fails")
        boolean ragEmbeddingFailFast = envBool("DATAAUDIT_AI_RAG_EMBEDDING_FAIL_FAST", false);

        @Option(names = "--rag-vector-store", description = "optional vector store backend, currently local-memory")
        String ragVectorStore = env("DATAAUDIT_AI_RAG_VECTOR_STORE", null);
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
                    ragRetriever(aiProviderOptions)).plan(profile);
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
                    ragRetriever(aiProviderOptions))
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

    @Command(name = "version", description = "Print build and runtime metadata.")
    static class VersionCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            BuildMetadata metadata = BuildMetadata.load();
            System.out.println("version=" + metadata.version);
            System.out.println("build_time=" + metadata.buildTime);
            System.out.println("commit_id=" + metadata.commitId);
            System.out.println("java_version=" + safeMetadata(System.getProperty("java.version")));
            return 0;
        }
    }

    @Command(
            name = "config",
            mixinStandardHelpOptions = true,
            description = "Initialize and validate task configuration.",
            subcommands = {ConfigInitCommand.class, ConfigValidateCommand.class}
    )
    static class ConfigCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "init", mixinStandardHelpOptions = true, description = "Create a starter task configuration.")
    static class ConfigInitCommand implements Callable<Integer> {
        @Option(names = {"-o", "--output"}, defaultValue = "task.yaml", description = "output task yaml path")
        private Path output;

        @Option(names = "--force", description = "overwrite an existing file")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            ConfigCheckResult result = configService().initialize(output, force);
            printChecks(result, "text");
            return result.isOk() ? 0 : 2;
        }
    }

    @Command(name = "validate", mixinStandardHelpOptions = true, description = "Validate a task configuration.")
    static class ConfigValidateCommand implements Callable<Integer> {
        @Option(names = {"-f", "--file"}, required = true, description = "task yaml path")
        private Path taskFile;

        @Option(names = "--test-connection", description = "open and probe source and target connectors")
        private boolean testConnection;

        @Option(names = "--format", defaultValue = "text", description = "text or json")
        private String format;

        @Override
        public Integer call() throws Exception {
            ConfigCheckResult result = configService().validate(taskFile, testConnection);
            printChecks(result, format);
            if (result.isOk()) {
                return 0;
            }
            return hasConnectionFailure(result) ? 4 : 2;
        }
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true, description = "Diagnose runtime readiness.")
    static class DoctorCommand implements Callable<Integer> {
        @Option(names = {"-f", "--file"}, description = "optional task yaml path")
        private Path taskFile;

        @Option(names = "--output-dir", defaultValue = ".", description = "directory to test for write access")
        private Path outputDir;

        @Option(names = "--test-connection", description = "open and probe configured source and target connectors")
        private boolean testConnection;

        @Option(names = "--format", defaultValue = "text", description = "text or json")
        private String format;

        @Override
        public Integer call() throws Exception {
            ConnectorRegistry registry = ConnectorRegistry.load();
            TaskConfigService configService = new TaskConfigService(registry, new SpecValidator(), System::getenv);
            ConfigCheckResult result = new DoctorService(registry, configService)
                    .diagnose(taskFile, outputDir, testConnection);
            printChecks(result, format);
            return result.isOk() ? 0 : 4;
        }
    }

    private static TaskConfigService configService() {
        return new TaskConfigService(ConnectorRegistry.load(), new SpecValidator(), System::getenv);
    }

    private static void printChecks(ConfigCheckResult result, String format) throws Exception {
        if ("json".equalsIgnoreCase(format)) {
            System.out.println(objectMapper().writeValueAsString(result));
            return;
        }
        for (ConfigCheckResult.Check check : result.checks) {
            System.out.println("[" + check.status.toUpperCase(Locale.ROOT) + "] "
                    + check.name + ": " + check.message);
        }
    }

    private static boolean hasConnectionFailure(ConfigCheckResult result) {
        return result.checks.stream()
                .anyMatch(check -> check.name.endsWith("_connection") && "error".equals(check.status));
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

    private static TaskFileSpec loadExecutionSpec(Path taskFile) throws Exception {
        TaskFileSpec spec = loadSpec(taskFile);
        List<String> issues = new SpecValidator().validate(spec);
        if (!issues.isEmpty()) {
            throw configurationError("Invalid task spec: " + String.join("; ", issues));
        }
        return spec;
    }

    static TaskFileSpec loadSpec(Path taskFile) throws Exception {
        return loadSpec(taskFile, System::getenv);
    }

    static TaskFileSpec loadSpec(Path taskFile, Function<String, String> envLookup) throws Exception {
        try (InputStream inputStream = Files.newInputStream(taskFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(inputStream);
            TaskFileSpec spec = objectMapper().convertValue(raw, TaskFileSpec.class);
            expandRuntimeEnvironment(spec, envLookup);
            return spec;
        }
    }

    private static void expandRuntimeEnvironment(TaskFileSpec spec, Function<String, String> envLookup) {
        if (spec == null) {
            return;
        }
        expandQueryConnector(spec.queryConnector, envLookup);
        expandEndpoint("source", spec.source, envLookup);
        expandEndpoint("target", spec.target, envLookup);
    }

    private static void expandQueryConnector(TaskFileSpec.QueryConnectorSpec connector,
                                             Function<String, String> envLookup) {
        if (connector == null) {
            return;
        }
        connector.uri = expandEnv(connector.uri, "query_connector.uri", envLookup);
        connector.user = expandEnv(connector.user, "query_connector.user", envLookup);
        connector.password = expandEnv(connector.password, "query_connector.password", envLookup);
    }

    private static void expandEndpoint(String label,
                                       TaskFileSpec.EndpointSpec endpoint,
                                       Function<String, String> envLookup) {
        if (endpoint == null) {
            return;
        }
        endpoint.url = expandEnv(endpoint.url, label + ".url", envLookup);
        endpoint.username = expandEnv(endpoint.username, label + ".username", envLookup);
        endpoint.password = expandEnv(endpoint.password, label + ".password", envLookup);
        endpoint.uri = expandEnv(endpoint.uri, label + ".uri", envLookup);
        endpoint.warehouse = expandEnv(endpoint.warehouse, label + ".warehouse", envLookup);
        endpoint.location = expandEnv(endpoint.location, label + ".location", envLookup);
    }

    private static String expandEnv(String value, String fieldPath, Function<String, String> envLookup) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = envLookup.apply(name);
            if (replacement == null) {
                throw configurationError("Missing environment variable " + name + " referenced by " + fieldPath);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static CommandLine.ParameterException configurationError(String message) {
        return new CommandLine.ParameterException(new CommandLine(new DataAuditMain()), message);
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
        RagRetriever retriever = ragRetriever(aiProviderOptions);
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
        AiProviderOptions options = new AiProviderOptions();
        options.ragCorpus = corpus;
        options.ragMode = mode;
        return ragRetriever(options);
    }

    private static RagRetriever ragRetriever(AiProviderOptions options) throws Exception {
        AiProviderOptions safeOptions = options == null ? new AiProviderOptions() : options;
        Path corpus = safeOptions.ragCorpus;
        String mode = safeOptions.ragMode;
        Path path = corpus == null ? defaultRagCorpus() : corpus;
        String normalizedMode = mode == null ? "hybrid" : mode.toLowerCase(Locale.ROOT);
        EmbeddingClient embeddingClient = embeddingClient(safeOptions);
        LocalCaseRetriever lexical = LocalCaseRetriever.fromDirectory(path);
        return switch (normalizedMode) {
            case "lexical" -> lexical;
            case "vector" -> vectorRetriever(lexical, embeddingClient, safeOptions.ragVectorStore);
            case "hybrid" -> new HybridCaseRetriever(lexical,
                    vectorRetriever(lexical, embeddingClient, safeOptions.ragVectorStore));
            default -> throw new IllegalArgumentException("Unsupported RAG mode: " + mode);
        };
    }

    private static RagRetriever vectorRetriever(LocalCaseRetriever lexical,
                                                EmbeddingClient embeddingClient,
                                                String vectorStoreBackend) {
        if (vectorStoreBackend != null && !vectorStoreBackend.isBlank()) {
            VectorStore store = new VectorStoreFactory().create(vectorStoreBackend, embeddingClient);
            store.index(lexical.cases());
            return new VectorStoreCaseRetriever(store, embeddingClient);
        }
        return new VectorCaseRetriever(lexical.cases(), embeddingClient);
    }

    private static EmbeddingClient embeddingClient(AiProviderOptions options) {
        EmbeddingProviderConfig config = new EmbeddingProviderConfig();
        config.provider = options.ragEmbeddingProvider;
        config.endpoint = options.ragEmbeddingEndpoint == null || options.ragEmbeddingEndpoint.isBlank()
                ? null
                : URI.create(options.ragEmbeddingEndpoint);
        config.apiKey = options.ragEmbeddingApiKey;
        config.model = options.ragEmbeddingModel;
        config.fallbackOnProviderError = !options.ragEmbeddingFailFast;
        return new EmbeddingClientFactory().create(config);
    }

    private static Path defaultRagCorpus() {
        Path examples = Paths.get("examples", "ai-copilot", "cases");
        return Files.isDirectory(examples) ? examples : null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeMetadata(String value) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return "unknown";
        }
        return value;
    }

    static class BuildMetadata {
        final String version;
        final String buildTime;
        final String commitId;

        BuildMetadata(String version, String buildTime, String commitId) {
            this.version = version;
            this.buildTime = buildTime;
            this.commitId = commitId;
        }

        static BuildMetadata load() {
            Attributes attributes = manifestAttributes();
            String packageVersion = DataAuditMain.class.getPackage() == null
                    ? null
                    : DataAuditMain.class.getPackage().getImplementationVersion();
            return new BuildMetadata(
                    safeMetadata(firstNonBlank(packageVersion, attributes.getValue("Implementation-Version"))),
                    safeMetadata(attributes.getValue("Build-Time")),
                    safeMetadata(attributes.getValue("Git-Commit")));
        }

        private static Attributes manifestAttributes() {
            try (InputStream input = DataAuditMain.class.getClassLoader()
                    .getResourceAsStream("META-INF/MANIFEST.MF")) {
                if (input == null) {
                    return new Attributes();
                }
                Attributes attributes = new Manifest(input).getMainAttributes();
                return "data-audit-cli".equals(attributes.getValue("Implementation-Title"))
                        ? attributes
                        : new Attributes();
            } catch (Exception ignored) {
                return new Attributes();
            }
        }

        private static String firstNonBlank(String first, String second) {
            return first == null || first.isBlank() ? second : first;
        }
    }

    private static int envInt(String name, int fallback) {
        String value = env(name, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long envLong(String name, long fallback) {
        String value = env(name, null);
        return value == null ? fallback : Long.parseLong(value);
    }

    private static boolean envBool(String name, boolean fallback) {
        String value = env(name, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
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
