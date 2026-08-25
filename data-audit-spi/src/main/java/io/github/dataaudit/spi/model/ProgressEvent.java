// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

import java.time.OffsetDateTime;

public class ProgressEvent {
    public OffsetDateTime timestamp = OffsetDateTime.now();
    public String taskName;
    public String runId;
    public String stage;
    public String sliceKey;
    public String status;
    public String limitType;
    public Long processedRows;
    public String message;

    public static ProgressEvent started(String taskName, String runId, String stage, String sliceKey) {
        return event(taskName, runId, stage, sliceKey, "started", null, null);
    }

    public static ProgressEvent completed(String taskName, String runId, String stage, String sliceKey) {
        return event(taskName, runId, stage, sliceKey, "completed", null, null);
    }

    public static ProgressEvent limitExceeded(String taskName,
                                              String runId,
                                              String stage,
                                              String sliceKey,
                                              String limitType,
                                              String message) {
        return event(taskName, runId, stage, sliceKey, "limit_exceeded", limitType, message);
    }

    private static ProgressEvent event(String taskName,
                                       String runId,
                                       String stage,
                                       String sliceKey,
                                       String status,
                                       String limitType,
                                       String message) {
        ProgressEvent event = new ProgressEvent();
        event.taskName = taskName;
        event.runId = runId;
        event.stage = stage;
        event.sliceKey = sliceKey;
        event.status = status;
        event.limitType = limitType;
        event.message = message;
        return event;
    }
}
