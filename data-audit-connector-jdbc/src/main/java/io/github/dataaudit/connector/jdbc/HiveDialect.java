package io.github.dataaudit.connector.jdbc;

public class HiveDialect extends AbstractSqlDialect {
    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
