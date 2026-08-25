// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.core;

import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.LinkedHashSet;
import java.util.Set;

final class ReadRequestFactory {
    private ReadRequestFactory() {
    }

    static ReadRequest baseRequest(TaskFileSpec spec) {
        ReadRequest request = new ReadRequest();
        request.boundaryType = spec.boundary.type;
        request.boundaryReference = spec.boundary.reference;
        request.columns.addAll(projectedColumns(spec, null));
        return request;
    }

    static ReadRequest sliceRequest(TaskFileSpec spec, String sliceColumn, String sliceValue) {
        ReadRequest request = baseRequest(spec);
        request.sliceColumn = sliceColumn;
        request.sliceValue = sliceValue;
        if (!request.columns.isEmpty() && sliceColumn != null && sliceValue != null && !request.columns.contains(sliceColumn)) {
            request.columns.add(sliceColumn);
        }
        return request;
    }

    static ReadRequest sampleRequest(TaskFileSpec spec,
                                     String sampleColumn,
                                     int sampleModulo,
                                     int sampleRemainder,
                                     String sliceColumn,
                                     String sliceValue) {
        ReadRequest request = sliceRequest(spec, sliceColumn, sliceValue);
        request.sampleColumn = sampleColumn;
        request.sampleModulo = sampleModulo;
        request.sampleRemainder = sampleRemainder;
        if (sampleColumn != null && !sampleColumn.isEmpty() && !request.columns.contains(sampleColumn)) {
            request.columns.add(sampleColumn);
        }
        return request;
    }

    static ReadRequest bucketRequest(TaskFileSpec spec,
                                     String bucketColumn,
                                     int bucketCount,
                                     int bucketId) {
        return sampleRequest(spec, bucketColumn, bucketCount, bucketId, null, null);
    }

    static boolean isVirtualBucket(String sliceKey) {
        return sliceKey != null && sliceKey.startsWith(SegmentEngine.VIRTUAL_BUCKET_PREFIX);
    }

    static int[] parseVirtualBucket(String sliceKey) {
        if (!isVirtualBucket(sliceKey)) {
            return null;
        }
        String encoded = sliceKey.substring(SegmentEngine.VIRTUAL_BUCKET_PREFIX.length());
        String[] pieces = encoded.split("/", 2);
        if (pieces.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(pieces[0]), Integer.parseInt(pieces[1])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Set<String> projectedColumns(TaskFileSpec spec, String extraColumn) {
        Set<String> columns = new LinkedHashSet<>();
        boolean explicitProjection = spec.object != null
                && spec.object.columns != null
                && !spec.object.columns.isEmpty();
        if (!explicitProjection) {
            return columns;
        }
        columns.addAll(spec.object.columns);
        if (spec.object != null && spec.object.key != null) {
            columns.addAll(spec.object.key);
        }
        if (extraColumn != null && !extraColumn.isEmpty()) {
            columns.add(extraColumn);
        }
        return columns;
    }
}
