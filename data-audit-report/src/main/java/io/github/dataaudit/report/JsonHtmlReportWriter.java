package io.github.dataaudit.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.report.ReportWriter;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonHtmlReportWriter implements ReportWriter {
    private final ObjectMapper objectMapper;
    private final Configuration configuration;

    public JsonHtmlReportWriter() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.configuration = new Configuration(Configuration.VERSION_2_3_33);
        this.configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "/templates");
        this.configuration.setDefaultEncoding("UTF-8");
        this.configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    }

    @Override
    public ReportArtifacts write(ReportModel report, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        prepareArtifactMetadata(report);
        Path jsonPath = outputDir.resolve("report.json");
        Path htmlPath = outputDir.resolve("report.html");
        Path suspectPath = outputDir.resolve("suspect_slices.csv");
        Path samplePath = outputDir.resolve("row_diff_sample.csv");
        Path manifestPath = outputDir.resolve("manifest.json");

        objectMapper.writeValue(jsonPath.toFile(), report);
        objectMapper.writeValue(manifestPath.toFile(), report.plan);

        Template template = configuration.getTemplate("report.ftl");
        Map<String, Object> model = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> reportView = objectMapper.convertValue(report, Map.class);
        model.put("report", reportView);
        try (BufferedWriter writer = Files.newBufferedWriter(htmlPath, StandardCharsets.UTF_8)) {
            template.process(model, writer);
        }

        writeSegmentsCsv(report, suspectPath);
        writeSamplesCsv(report, samplePath);
        return new ReportArtifacts(jsonPath, htmlPath);
    }

    private void prepareArtifactMetadata(ReportModel report) {
        if (report == null) {
            return;
        }
        if (report.taskName == null && report.plan != null) {
            report.taskName = report.plan.taskName;
        }
        if (report.createdAt == null) {
            report.createdAt = report.generatedAt;
        }
    }

    private void writeSegmentsCsv(ReportModel report, Path path) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("slice_key,slice_type,row_estimate,drilldownable,reason");
            writer.newLine();
            for (SliceDescriptor slice : report.result.suspectSlices) {
                writer.write(csv(slice.sliceKey) + "," + csv(slice.sliceType) + "," + csv(slice.rowEstimate == null ? null : String.valueOf(slice.rowEstimate)) + ","
                        + csv(String.valueOf(slice.drilldownable)) + "," + csv(slice.reason));
                writer.newLine();
            }
        }
    }

    private void writeSamplesCsv(ReportModel report, Path path) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("type,key,source_value,target_value,slice_key");
            writer.newLine();
            for (DiffResult.DiffSample sample : report.result.diff.samples) {
                writer.write(csv(sample.type) + "," + csv(sample.key) + "," + csv(sample.sourceValue) + "," + csv(sample.targetValue) + "," + csv(sample.sliceKey));
                writer.newLine();
            }
        }
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
