package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class ReadRequest {
    public List<String> columns = new ArrayList<>();
    public String segmentColumn;
    public String segmentValue;
    public Long limit;
}

