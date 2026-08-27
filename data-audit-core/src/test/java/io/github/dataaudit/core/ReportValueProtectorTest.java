// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReportModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportValueProtectorTest {

    @Test
    void shouldMaskEvidenceValuesButKeepKeysByDefault() {
        ReportModel report = reportWithSample("key-1", "source-secret", "target-secret");

        ReportModel protectedReport = new ReportValueProtector().protect(report, null);

        assertSame(report, protectedReport);
        assertEquals("masked", report.evidenceValueMode);
        assertEquals("key-1", report.result.diff.samples.get(0).key,
                "the row key is the investigator's only lead and stays readable");
        assertEquals("***", report.result.diff.samples.get(0).sourceValue);
        assertEquals("***", report.result.diff.samples.get(0).targetValue);
        assertEquals("dt=2026-07-27", report.result.diff.samples.get(0).sliceKey);
        assertEquals("DIFF_FOUND", report.result.status);
    }

    @Test
    void shouldHashEvidenceValuesWithStableSha256() {
        ReportModel report = reportWithSample("key-1", "secret", null);

        new ReportValueProtector().protect(report, "hash");

        String expected = "sha256:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b";
        assertEquals("hash", report.evidenceValueMode);
        assertEquals("key-1", report.result.diff.samples.get(0).key);
        assertEquals(expected, report.result.diff.samples.get(0).sourceValue);
        assertNull(report.result.diff.samples.get(0).targetValue);
    }

    @Test
    void shouldOmitOrPreserveRawValuesWhenExplicitlyRequested() {
        ReportModel omitted = reportWithSample("key", "source", "target");
        new ReportValueProtector().protect(omitted, "omit");
        assertNull(omitted.result.diff.samples.get(0).key);
        assertNull(omitted.result.diff.samples.get(0).sourceValue);
        assertNull(omitted.result.diff.samples.get(0).targetValue);

        ReportModel raw = reportWithSample("key", "source", "target");
        new ReportValueProtector().protect(raw, "raw");
        assertEquals("raw", raw.evidenceValueMode);
        assertEquals("key", raw.result.diff.samples.get(0).key);
        assertEquals("source", raw.result.diff.samples.get(0).sourceValue);
        assertEquals("target", raw.result.diff.samples.get(0).targetValue);
    }

    @Test
    void shouldRejectUnsupportedMode() {
        ReportModel report = reportWithSample("key", "source", "target");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ReportValueProtector().protect(report, "encrypt"));

        assertEquals("Unsupported output.value_mode: encrypt", error.getMessage());
    }

    private ReportModel reportWithSample(String key, String sourceValue, String targetValue) {
        ReportModel report = new ReportModel();
        report.result.status = "DIFF_FOUND";
        DiffResult.DiffSample sample = new DiffResult.DiffSample();
        sample.key = key;
        sample.sourceValue = sourceValue;
        sample.targetValue = targetValue;
        sample.sliceKey = "dt=2026-07-27";
        report.result.diff.samples.add(sample);
        return report;
    }
}
