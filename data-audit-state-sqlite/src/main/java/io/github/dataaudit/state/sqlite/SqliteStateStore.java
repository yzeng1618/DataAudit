package io.github.dataaudit.state.sqlite;

import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.ReportModel;
import io.github.dataaudit.spi.model.RunState;
import io.github.dataaudit.spi.model.SegmentDescriptor;
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
            statement.executeUpdate("create table if not exists run_record (run_id text primary key, task_name text, boundary_fingerprint text, selected_path text, status text, report_json_path text, report_html_path text, started_at text, finished_at text)");
            statement.executeUpdate("create table if not exists segment_record (run_id text, segment_key text, status text, resume_token text, source_digest text, target_digest text)");
        }
    }

    @Override
    public RunState startRun(String taskName, String boundaryFingerprint, ExecutionPlan plan) throws Exception {
        String runId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into run_record(run_id, task_name, boundary_fingerprint, selected_path, status, started_at) values(?,?,?,?,?,?)")) {
            statement.setString(1, runId);
            statement.setString(2, taskName);
            statement.setString(3, boundaryFingerprint);
            statement.setString(4, plan.selectedPath);
            statement.setString(5, "RUNNING");
            statement.setString(6, now.toString());
            statement.executeUpdate();
        }
        RunState state = new RunState();
        state.runId = runId;
        state.taskName = taskName;
        state.boundaryFingerprint = boundaryFingerprint;
        state.selectedPath = plan.selectedPath;
        state.status = "RUNNING";
        state.startedAt = now;
        return state;
    }

    @Override
    public void saveSegments(String runId, List<SegmentDescriptor> segments, String status) throws Exception {
        try (Connection connection = getConnection()) {
            try (PreparedStatement delete = connection.prepareStatement("delete from segment_record where run_id = ?")) {
                delete.setString(1, runId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into segment_record(run_id, segment_key, status, resume_token, source_digest, target_digest) values(?,?,?,?,?,?)")) {
                for (SegmentDescriptor segment : segments) {
                    insert.setString(1, runId);
                    insert.setString(2, segment.segmentKey);
                    insert.setString(3, status);
                    insert.setString(4, segment.segmentKey);
                    insert.setString(5, segment.sourceDigest);
                    insert.setString(6, segment.targetDigest);
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
        state.selectedPath = resultSet.getString("selected_path");
        state.status = resultSet.getString("status");
        String startedAt = resultSet.getString("started_at");
        String finishedAt = resultSet.getString("finished_at");
        state.startedAt = startedAt == null ? null : OffsetDateTime.parse(startedAt);
        state.finishedAt = finishedAt == null ? null : OffsetDateTime.parse(finishedAt);
        state.reportJsonPath = resultSet.getString("report_json_path");
        state.reportHtmlPath = resultSet.getString("report_html_path");
        state.segments = listSegments(state.runId);
        return state;
    }

    private List<RunState.SegmentState> listSegments(String runId) throws Exception {
        List<RunState.SegmentState> segments = new ArrayList<RunState.SegmentState>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from segment_record where run_id = ? order by segment_key")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    RunState.SegmentState state = new RunState.SegmentState();
                    state.segmentKey = resultSet.getString("segment_key");
                    state.status = resultSet.getString("status");
                    state.resumeToken = resultSet.getString("resume_token");
                    state.sourceDigest = resultSet.getString("source_digest");
                    state.targetDigest = resultSet.getString("target_digest");
                    segments.add(state);
                }
            }
        }
        return segments;
    }

    private Connection getConnection() throws Exception {
        if (jdbcUrl == null) {
            throw new IllegalStateException("State store is not initialized");
        }
        return DriverManager.getConnection(jdbcUrl);
    }
}
