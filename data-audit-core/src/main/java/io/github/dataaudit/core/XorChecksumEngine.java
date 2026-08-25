// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.connector.RowStreamReader;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.TaskFileSpec;

public class XorChecksumEngine {
    private final NormalizationService normalizationService = new NormalizationService();
    private final HashProvider hashProvider = new HashProvider();

    public String checksum(TaskFileSpec spec, RowStreamReader reader) throws Exception {
        ReadRequest request = ReadRequestFactory.baseRequest(spec);
        final long[] accumulator = new long[] {0L};
        reader.scanRows(request, row -> {
            String canonical = normalizationService.canonicalRow(normalizationService.normalizeRow(spec, row));
            accumulator[0] ^= hashProvider.hash64(canonical);
        });
        return Long.toUnsignedString(accumulator[0]);
    }
}
