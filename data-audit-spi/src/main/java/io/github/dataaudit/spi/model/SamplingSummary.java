// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

public class SamplingSummary {
    public boolean used;
    public String mode;
    public String sampleColumn;
    public Integer sampleModulo;
    public Integer sampleRemainder;
    public Long sourceRows;
    public Long targetRows;
}
