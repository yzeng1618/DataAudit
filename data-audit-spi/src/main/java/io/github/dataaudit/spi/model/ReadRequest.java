package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

public class ReadRequest {
    public List<String> columns = new ArrayList<>();
    public String sliceColumn;
    public String sliceValue;
    public String boundaryType;
    public String boundaryReference;
    public Long limit;
    public Integer bucketCount;
    public Integer bucketId;
    public String sampleColumn;
    public Integer sampleModulo;
    public Integer sampleRemainder;
}
