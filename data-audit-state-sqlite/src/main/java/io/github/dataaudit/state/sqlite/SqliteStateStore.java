// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.state.sqlite;

import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SliceDescriptor;
import io.github.dataaudit.spi.state.StateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SqliteStateStore implements StateStore {
    private final Path databasePath;
    private String jdbcUrl;

    public SqliteStateStore(Path databasePath) {
        this.databasePath = databasePath;
    }

    @Override
    public void initialize() throws Exception {
        if (databasePath.getParent() != null) {
            Files.createDirectories(databasePath.getParent());
        }
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists run_record (run_id text primary key, task_name text, boundary_fingerprint text, status text, report_json_path text, report_html_path text, started_at text, finished_at text)");
            statement.executeUpdate("create table if not exists slice_record (run_id text, slice_key text, status text, resume_token text)");
        }
    }

    @Override
    public RunState startRun(String taskName, String boundaryFingerprint, ExecutionPlan plan) throws Exception {
        String runId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into run_record(run_id, task_name, boundary_fingerprint, status, started_at) values(?,?,?,?,?)")) {
            statement.setString(1, runId);
            statement.setString(2, taskName);
            statement.setString(3, boundaryFingerprint);
            statement.setString(4, "RUNNING");
            statement.setString(5, now.toString());
            statement.executeUpdate();
        }
        RunState state = new RunState();
        state.runId = runId;
        state.taskName = taskName;
        state.boundaryFingerprint = boundaryFingerprint;
        state.status = "RUNNING";
        state.startedAt = now;
        return state;
    }

    @Override
    public void saveSlices(String runId, List<SliceDescriptor> slices, String status) throws Exception {
        try (Connection connection = getConnection()) {
            try (PreparedStatement delete = connection.prepareStatement("delete from slice_record where run_id = ?")) {
                delete.setString(1, runId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into slice_record(run_id, slice_key, status, resume_token) values(?,?,?,?)")) {
                for (SliceDescriptor slice : slices) {
                    insert.setString(1, runId);
                    insert.setString(2, slice.sliceKey);
                    insert.setString(3, status);
                    insert.setString(4, slice.sliceKey);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    @Override
    public void completeRun(String runId, String status, Path reportJsonPath, Path reportHtmlPath) throws Exception {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update run_record set status = ?, report_json_path = ?, report_html_path = ?, finished_at = ? where run_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, reportJsonPath == null ? null : reportJsonPath.toAbsolutePath().toString());
            statement.setString(3, reportHtmlPath == null ? null : reportHtmlPath.toAbsolutePath().toString());
            statement.setString(4, OffsetDateTime.now().toString());
            statement.setString(5, runId);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<RunState> findLatestRun(String taskName) throws Exception {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from run_record where task_name = ? order by started_at desc limit 1")) {
            statement.setString(1, taskName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRunState(resultSet));
            }
        }
    }

    @Override
    public Optional<RunState> findRun(String runId) throws Exception {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("select * from run_record where run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRunState(resultSet));
            }
        }
    }

    @Override
    public void attachReport(String runId, ReportModel report) {
        // Report paths are already persisted by completeRun. Keeping this method for future state/report linkage.
    }

    private RunState mapRunState(ResultSet resultSet) throws Exception {
        RunState state = new RunState();
        state.runId = resultSet.getString("run_id");
        state.taskName = resultSet.getString("task_name");
        state.boundaryFingerprint = resultSet.getString("boundary_fingerprint");
        state.status = resultSet.getString("status");
        String startedAt = resultSet.getString("started_at");
        String finishedAt = resultSet.getString("finished_at");
        state.startedAt = startedAt == null ? null : OffsetDateTime.parse(startedAt);
        state.finishedAt = finishedAt == null ? null : OffsetDateTime.parse(finishedAt);
        state.reportJsonPath = resultSet.getString("report_json_path");
        state.reportHtmlPath = resultSet.getString("report_html_path");
        state.slices = listSlices(state.runId);
        return state;
    }

    private List<RunState.SliceState> listSlices(String runId) throws Exception {
        List<RunState.SliceState> slices = new ArrayList<RunState.SliceState>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from slice_record where run_id = ? order by slice_key")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    RunState.SliceState state = new RunState.SliceState();
                    state.sliceKey = resultSet.getString("slice_key");
                    state.status = resultSet.getString("status");
                    state.resumeToken = resultSet.getString("resume_token");
                    slices.add(state);
                }
            }
        }
        return slices;
    }

    private Connection getConnection() throws Exception {
        if (jdbcUrl == null) {
            throw new IllegalStateException("State store is not initialized");
        }
        return DriverManager.getConnection(jdbcUrl);
    }
}
