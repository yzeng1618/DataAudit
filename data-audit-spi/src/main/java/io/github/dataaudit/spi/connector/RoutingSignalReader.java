// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.spi.connector;

import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SliceSignal;

import java.util.List;

/**
 * Optional pushdown of routing-digest aggregation, used on {@code xlarge}
 * objects to bucket rows by a routing expression remotely instead of scanning
 * them. Only present when the bundle advertises
 * {@code supportsRoutingSignalPushdown}.
 */
public interface RoutingSignalReader {

    /**
     * Computes one signal per routing bucket; {@code sliceType} is
     * {@code "routing"} and {@code sliceKey} carries the bucket identity
     * (for example {@code routing=3}).
     *
     * @throws Exception on read failure
     */
    List<SliceSignal> readRoutingSignals(ReadRequest request) throws Exception;
}
