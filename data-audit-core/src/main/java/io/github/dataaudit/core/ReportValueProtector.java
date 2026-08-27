// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.DiffResult;
import io.github.dataaudit.spi.model.ReportModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

public class ReportValueProtector {
    private static final String DEFAULT_MODE = "masked";
    private static final Set<String> SUPPORTED_MODES = Set.of("masked", "hash", "omit", "raw");

    public ReportModel protect(ReportModel report, String valueMode) {
        if (report == null) {
            return null;
        }
        String effectiveMode = normalize(valueMode);
        if (!SUPPORTED_MODES.contains(effectiveMode)) {
            throw new IllegalArgumentException("Unsupported output.value_mode: " + effectiveMode);
        }

        report.evidenceValueMode = effectiveMode;
        if (report.result == null || report.result.diff == null || report.result.diff.samples == null) {
            return report;
        }
        for (DiffResult.DiffSample sample : report.result.diff.samples) {
            if (sample == null) {
                continue;
            }
            // The row key is usually a business identifier, not a secret, and it is the
            // one thing an investigator needs; only "omit" suppresses it.
            sample.key = "omit".equals(effectiveMode) ? null : sample.key;
            sample.sourceValue = protectValue(sample.sourceValue, effectiveMode);
            sample.targetValue = protectValue(sample.targetValue, effectiveMode);
        }
        return report;
    }

    private String normalize(String valueMode) {
        if (valueMode == null || valueMode.isBlank()) {
            return DEFAULT_MODE;
        }
        return valueMode.trim().toLowerCase(Locale.ROOT);
    }

    private String protectValue(String value, String mode) {
        if (value == null) {
            return null;
        }
        return switch (mode) {
            case "masked" -> "***";
            case "hash" -> sha256(value);
            case "omit" -> null;
            case "raw" -> value;
            default -> throw new IllegalStateException("Unhandled output.value_mode: " + mode);
        };
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
