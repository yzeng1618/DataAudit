package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.SchemaModel;

public interface SchemaReader {
    SchemaModel readSchema() throws Exception;
}
