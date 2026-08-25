// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli;

import io.github.dataaudit.core.SpecValidator;
import io.github.dataaudit.spi.model.TaskFileSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateContractTest {

    @Test
    void everyRepositoryTemplateStrictlyLoadsAndSemanticallyValidates() throws Exception {
        Path templateDir = Path.of("..", "templates").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(templateDir), "template directory is missing: " + templateDir);

        List<Path> templates;
        try (Stream<Path> files = Files.list(templateDir)) {
            templates = files
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }

        assertFalse(templates.isEmpty(), "no repository templates found");
        for (Path template : templates) {
            TaskFileSpec spec = DataAuditMain.loadSpec(template, ignored -> "test-value");
            List<String> issues = new SpecValidator().validate(spec);
            assertTrue(issues.isEmpty(), () -> template.getFileName() + ": " + String.join("; ", issues));
        }
    }
}
