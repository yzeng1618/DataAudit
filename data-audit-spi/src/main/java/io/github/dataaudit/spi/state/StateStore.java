// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.state;

import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SliceDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for run state: boundary fingerprints, suspect slices, and report
 * locations, so a later invocation can resume or re-check only the affected
 * scope. The engine drives the lifecycle
 * {@link #initialize()} → {@link #startRun} → {@link #saveSlices} →
 * {@link #completeRun}; lookups serve resume and reporting.
 *
 * <p>Implementations are used from a single thread per run and should be
 * idempotent where practical (re-running {@code initialize} must be safe).
 */
public interface StateStore {

    /** Creates or migrates the underlying storage; safe to call repeatedly. */
    void initialize() throws Exception;

    /**
     * Registers a new run and returns its state, including the generated
     * {@code runId} used by every subsequent call.
     */
    RunState startRun(String taskName, String boundaryFingerprint, ExecutionPlan plan) throws Exception;

    /** Records the slices touched by a run together with their status. */
    void saveSlices(String runId, List<SliceDescriptor> slices, String status) throws Exception;

    /** Marks a run finished with its final status and report artifact paths. */
    void completeRun(String runId, String status, Path reportJsonPath, Path reportHtmlPath) throws Exception;

    /** Latest run of a task, if any — the anchor for re-check and resume. */
    Optional<RunState> findLatestRun(String taskName) throws Exception;

    /** Looks up one run by id. */
    Optional<RunState> findRun(String runId) throws Exception;

    /** Attaches the full report model to a run for later retrieval. */
    void attachReport(String runId, ReportModel report) throws Exception;
}
