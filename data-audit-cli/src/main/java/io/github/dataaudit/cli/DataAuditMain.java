package io.github.dataaudit.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                DataAuditMain.ReportCommand.class
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

        @Override
        public Integer call() throws Exception {
            TaskFileSpec spec = loadSpec(taskFile);
            ExecutionService service = newExecutionService(spec);
            ReportModel report = service.check(spec);
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

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private static void printSummary(ReportModel report) throws Exception {
        System.out.println("runId=" + report.runId);
        System.out.println("status=" + report.result.status);
        System.out.println("objectClass=" + report.plan.objectClass);
        System.out.println("selectedPath=" + report.plan.selectedPath);
        System.out.println("signalBackend=" + report.plan.signalBackend);
        System.out.println("consistencyLevel=" + report.result.consistencyLevel);
        System.out.println("verdictBasis=" + report.result.verdictBasis);
        System.out.println("rootCause=" + report.result.rootCause);
        System.out.println("suspectSlices=" + objectMapper().writeValueAsString(report.result.suspectSlices));
        System.out.println("resumeHint=" + report.result.resumeHint);
        System.out.println("planDecisionTrace=" + objectMapper().writeValueAsString(report.plan.decisionTrace));
        System.out.println("resultDecisionTrace=" + objectMapper().writeValueAsString(report.result.decisionTrace));
        System.out.println("dmlVerdict=" + report.result.dmlAudit.verdict);
        System.out.println("ddlVerdict=" + report.result.ddlAudit.verdict);
    }

    private static int exitCode(String status) {
        if ("CONSISTENT".equalsIgnoreCase(status)) {
            return 0;
        }
        if ("DIFF_FOUND".equalsIgnoreCase(status)) {
            return 1;
        }
        if ("INCONCLUSIVE".equalsIgnoreCase(status)) {
            return 3;
        }
        if ("PARTIAL".equalsIgnoreCase(status)) {
            return 4;
        }
        if ("REFUSED".equalsIgnoreCase(status)) {
            return 5;
        }
        return 2;
    }
}
