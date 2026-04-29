package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.TableProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.TemporalAccessor;
import java.util.HexFormat;
import java.util.Locale;

public class SampleMasker {
    public TableProfile.SampleProfile mask(String field, Object value) {
        TableProfile.SampleProfile sample = new TableProfile.SampleProfile();
        sample.field = field;
        sample.masked = true;
        sample.pattern = pattern(value);
        sample.hash = sha256(field + ":" + String.valueOf(value));
        sample.summary = "masked sample summary; raw value is not persisted";
        return sample;
    }

    private String pattern(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number number) {
            String text = String.valueOf(number);
            return text.contains(".") ? "decimal_digits:" + text.replace("-", "").length() : "integer_digits:" + text.replace("-", "").length();
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof TemporalAccessor) {
            return "temporal";
        }
        String text = String.valueOf(value);
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("{") && lower.endsWith("}")) || (lower.startsWith("[") && lower.endsWith("]"))) {
            return "json_like:length=" + text.length();
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return "temporal_text";
        }
        return "text:length=" + text.length();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash masked sample", e);
        }
    }
}
