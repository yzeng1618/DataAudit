// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.ScaleClass;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.Locale;

public class ScaleClassifier {
    private static final long SMALL_ROW_THRESHOLD = 100_000L;
    private static final long XLARGE_ROW_THRESHOLD = 100_000_000L;
    private static final long SMALL_BYTE_THRESHOLD = 300L * 1024 * 1024;
    private static final long XLARGE_BYTE_THRESHOLD = 30L * 1024 * 1024 * 1024;

    public ScaleClass classify(TaskFileSpec spec) {
        if (spec != null
                && spec.planner != null
                && spec.planner.scaleOverride != null
                && !spec.planner.scaleOverride.trim().isEmpty()) {
            return ScaleClass.valueOf(spec.planner.scaleOverride.trim().toUpperCase(Locale.ROOT));
        }

        long estimatedRows = spec == null || spec.object == null || spec.object.estimatedRows == null ? -1L : spec.object.estimatedRows;
        long estimatedBytes = spec == null || spec.object == null || spec.object.estimatedBytes == null ? -1L : spec.object.estimatedBytes;

        if (estimatedRows < 0L && estimatedBytes < 0L) {
            return ScaleClass.LARGE;
        }

        if (estimatedRows >= 0L
                && estimatedRows <= SMALL_ROW_THRESHOLD
                && (estimatedBytes < 0L || estimatedBytes <= SMALL_BYTE_THRESHOLD)) {
            return ScaleClass.SMALL;
        }

        if ((estimatedRows >= 0L && estimatedRows > XLARGE_ROW_THRESHOLD)
                || (estimatedBytes >= 0L && estimatedBytes > XLARGE_BYTE_THRESHOLD)) {
            return ScaleClass.XLARGE;
        }

        return ScaleClass.LARGE;
    }
}
