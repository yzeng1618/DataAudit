// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.model;

/**
 * Aggregate signal for one slice of an object: {@code sliceType} names the
 * grouping dimension (for example {@code "partition"} or {@code "routing"}),
 * {@code sliceKey} identifies the member (for example {@code dt=2026-03-10}),
 * and {@code rowCount}/{@code checksum} are compared between source and target
 * to localize a mismatch before exact diffing.
 */
public class SliceSignal {
    public String sliceKey;
    public String sliceType;
    public long rowCount;
    public String checksum;
}
