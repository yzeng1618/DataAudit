// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;

import java.util.Map;

/**
 * Streams rows for exact and sampled diffs. Implementations must not
 * materialize the whole result: rows are pushed one at a time so the engine
 * can enforce its own memory governance.
 */
public interface RowStreamReader {

    /**
     * Scans the rows selected by the request (columns, optional slice, bucket,
     * or sampling filter) and hands each row to the visitor exactly once. Keys
     * of the row map are the requested column names.
     *
     * @throws Exception on read failure, or rethrown from the visitor to abort
     *                   the scan
     */
    void scanRows(ReadRequest request, RowVisitor visitor) throws Exception;

    /** Row callback; throwing aborts the scan and propagates to the caller. */
    @FunctionalInterface
    interface RowVisitor {
        void accept(Map<String, Object> row) throws Exception;
    }
}
