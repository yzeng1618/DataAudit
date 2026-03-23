package io.github.dataaudit.spi.state;

import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SliceDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface StateStore {
    void initialize() throws Exception;

    RunState startRun(String taskName, String boundaryFingerprint, ExecutionPlan plan) throws Exception;

    void saveSlices(String runId, List<SliceDescriptor> slices, String status) throws Exception;

    void completeRun(String runId, String status, Path reportJsonPath, Path reportHtmlPath) throws Exception;

    Optional<RunState> findLatestRun(String taskName) throws Exception;

    Optional<RunState> findRun(String runId) throws Exception;

    void attachReport(String runId, ReportModel report) throws Exception;
}
