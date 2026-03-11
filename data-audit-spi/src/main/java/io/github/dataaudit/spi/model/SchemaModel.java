package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class SchemaModel {
    public List<Column> columns = new ArrayList<>();

    public static class Column {
        public String name;
        public String logicalName;
        public String type;
        public boolean nullable = true;
    }
}

