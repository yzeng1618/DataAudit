// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.connector.jdbc;

import io.github.dataaudit.spi.model.TaskFileSpec;

import java.util.Locale;

public class SqlDialectResolver {
    public SqlDialect resolve(TaskFileSpec.EndpointSpec endpointSpec) {
        String dialect = explicitDialect(endpointSpec);
        if (dialect == null) {
            dialect = inferFromUrl(endpointSpec == null ? null : endpointSpec.url);
        }
        if (dialect == null) {
            return new PostgresDialect();
        }
        String normalized = dialect.toLowerCase(Locale.ROOT);
        if ("postgres".equals(normalized) || "postgresql".equals(normalized)) {
            return new PostgresDialect();
        }
        if ("mysql".equals(normalized)) {
            return new MySqlDialect();
        }
        if ("hive".equals(normalized) || "hiveserver2".equals(normalized)) {
            return new HiveDialect();
        }
        if ("doris".equals(normalized)) {
            return new DorisDialect();
        }
        return new PostgresDialect();
    }

    private String explicitDialect(TaskFileSpec.EndpointSpec endpointSpec) {
        if (endpointSpec == null || endpointSpec.options == null) {
            return null;
        }
        Object value = endpointSpec.options.get("dialect");
        return value == null ? null : String.valueOf(value);
    }

    private String inferFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (normalized.startsWith("jdbc:mysql:")) {
            return "mysql";
        }
        if (normalized.startsWith("jdbc:hive2:")) {
            return "hive";
        }
        if (normalized.startsWith("jdbc:doris:")) {
            return "doris";
        }
        return null;
    }
}
