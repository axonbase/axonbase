package com.axonbase.jdbc.hibernate;

import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.type.SqlTypes;

public class AxonBaseDialect extends Dialect {

    public AxonBaseDialect() {
    }

    @Override
    public DatabaseVersion getVersion() {
        return DatabaseVersion.make(0, 1, 0);
    }

    @Override
    public String columnType(int sqlTypeCode) {
        return switch (sqlTypeCode) {
            case SqlTypes.VARCHAR, SqlTypes.CHAR, SqlTypes.LONGVARCHAR,
                 SqlTypes.CLOB, SqlTypes.NCHAR, SqlTypes.NVARCHAR,
                 SqlTypes.LONGNVARCHAR, SqlTypes.NCLOB -> "string";
            case SqlTypes.INTEGER, SqlTypes.BIGINT, SqlTypes.SMALLINT,
                 SqlTypes.TINYINT -> "int";
            case SqlTypes.BOOLEAN, SqlTypes.BIT -> "bool";
            case SqlTypes.FLOAT, SqlTypes.DOUBLE, SqlTypes.REAL -> "float";
            case SqlTypes.NUMERIC, SqlTypes.DECIMAL -> "decimal";
            case SqlTypes.DATE, SqlTypes.TIME, SqlTypes.TIMESTAMP -> "datetime";
            case SqlTypes.BLOB, SqlTypes.VARBINARY, SqlTypes.BINARY,
                 SqlTypes.LONGVARBINARY, SqlTypes.LONG32VARBINARY -> "bytes";
            default -> super.columnType(sqlTypeCode);
        };
    }

    @Override
    public String currentTimestamp() {
        return "time::now()";
    }

    @Override
    public boolean supportsIfExistsBeforeTableName() {
        return false;
    }

    @Override
    public boolean supportsIfExistsAfterTableName() {
        return false;
    }
}