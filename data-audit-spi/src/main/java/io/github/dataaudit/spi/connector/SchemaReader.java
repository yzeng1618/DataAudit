package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.SchemaModel;

/**
 * Reads the physical column inventory of the opened endpoint. The engine uses
 * it to validate configured columns, plan projections, and detect DDL drift
 * between source and target.
 */
public interface SchemaReader {

    /**
     * Returns the endpoint's columns in their physical order. Never
     * {@code null}; an empty column list means the object could not be
     * described.
     *
     * @throws Exception on connectivity or permission problems; reported as an
     *                   execution failure
     */
    SchemaModel readSchema() throws Exception;
}
