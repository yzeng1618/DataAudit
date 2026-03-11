package io.github.dataaudit.connector.jdbc;

public class DorisDialect extends AbstractSqlDialect {
    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
