// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Scope of one read issued to a connector: which columns to produce and an
 * optional narrowing to a slice ({@code sliceColumn}/{@code sliceValue}), a
 * hash bucket ({@code bucketCount}/{@code bucketId}), or a modulo sample
 * ({@code sampleColumn}/{@code sampleModulo}/{@code sampleRemainder}).
 * Unset fields mean "no restriction"; boundary fields echo the resolved audit
 * boundary so pushdown queries can pin snapshot-consistent reads.
 */
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
