// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.report;

import io.github.dataaudit.spi.model.ReportModel;

import java.nio.file.Path;

/**
 * Persists a finished {@link ReportModel} as user-facing artifacts. The
 * default implementation writes {@code report.json} and {@code report.html}
 * (plus CSV side files) into the output directory; evidence values arrive
 * already masked according to {@code output.value_mode}, so writers must not
 * re-expand them.
 */
public interface ReportWriter {

    /**
     * Writes the report artifacts into {@code outputDir}, creating it if
     * needed, and returns the primary artifact paths.
     *
     * @throws Exception if the artifacts cannot be written
     */
    ReportArtifacts write(ReportModel report, Path outputDir) throws Exception;

    /** Locations of the primary artifacts produced by {@link #write}. */
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
