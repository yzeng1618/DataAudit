package io.github.dataaudit.state.sqlite;

import io.github.dataaudit.spi.model.BoundaryRef;
import io.github.dataaudit.spi.model.ExecutionPlan;
import io.github.dataaudit.spi.model.RunState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStateStoreTest {
    @Test
    void shouldPersistAndLoadRun() throws Exception {
        Path tempFile = Files.createTempFile("recon-state", ".db");
        SqliteStateStore store = new SqliteStateStore(tempFile);
        store.initialize();
        ExecutionPlan plan = new ExecutionPlan();
        plan.boundary = new BoundaryRef();
        plan.selectedPath = "schema -> exact diff";
        RunState state = store.startRun("demo", "fingerprint", plan);
        store.completeRun(state.runId, "CONSISTENT", tempFile.resolveSibling("report.json"), tempFile.resolveSibling("report.html"));
        Optional<RunState> reloaded = store.findRun(state.runId);
        assertTrue(reloaded.isPresent());
        assertEquals("CONSISTENT", reloaded.get().status);
    }
}
