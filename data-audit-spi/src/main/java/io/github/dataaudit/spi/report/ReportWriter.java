package io.github.dataaudit.spi.report;

import io.github.dataaudit.spi.model.ReportModel;

import java.nio.file.Path;

public interface ReportWriter {
    ReportArtifacts write(ReportModel report, Path outputDir) throws Exception;

    final class ReportArtifacts {
        private final Path jsonPath;
        private final Path htmlPath;

        public ReportArtifacts(Path jsonPath, Path htmlPath) {
            this.jsonPath = jsonPath;
            this.htmlPath = htmlPath;
        }

        public Path getJsonPath() {
            return jsonPath;
        }

        public Path getHtmlPath() {
            return htmlPath;
        }
    }
}
