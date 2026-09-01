package com.axonbase.jdbc;

import com.axonbase.value.AxonValue;

import java.sql.*;
import java.util.*;

/**
 * Metadados do banco para o driver JDBC AxonBase.
 * Implementa apenas o necessário para o DataGrip descobrir tabelas e colunas.
 */
public class AxonDatabaseMetaData implements DatabaseMetaData {

    private final AxonConnection connection;

    public AxonDatabaseMetaData(AxonConnection connection) {
        this.connection = connection;
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        List<AxonValue> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            List<String> names = resolveTables(stmt, tableNamePattern);
            String cat = db();
            String schema = ns();
            for (String name : names) {
                rows.add(AxonValue.object(Map.of(
                    "TABLE_NAME", AxonValue.str(name),
                    "TABLE_TYPE", AxonValue.str("TABLE"),
                    "TABLE_CAT", AxonValue.str(cat),
                    "TABLE_SCHEM", AxonValue.str(schema),
                    "REMARKS", AxonValue.str("")
                )));
            }
        }
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(rows)));
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        List<AxonValue> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            List<String> tables = resolveTables(stmt, tableNamePattern);
            for (String tableName : tables) {
                int ordinal = 0;
                rows.add(columnRow("id", tableName, db(), ns(), Types.VARCHAR, "VARCHAR", columnNoNulls, ++ordinal));
                try {
                    ResultSet sample = stmt.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");
                    ResultSetMetaData md = sample.getMetaData();
                    Set<String> seen = new LinkedHashSet<>();
                    seen.add("id");
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String col = md.getColumnName(i);
                        if (col != null && !col.isEmpty() && seen.add(col)) {
                            rows.add(columnRow(col, tableName, db(), ns(), Types.VARCHAR, "VARCHAR", columnNullable, ++ordinal));
                        }
                    }
                } catch (Exception e) {
                    // tabela vazia ou erro: segue com apenas a coluna id
                }
            }
        }
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(rows)));
    }

    private List<String> resolveTables(Statement stmt, String tableNamePattern) throws SQLException {
        List<String> names = new ArrayList<>();
        try {
            ResultSet rs = stmt.executeQuery("INFO FOR DATABASE");
            if (rs.next()) {
                Object tablesVal = rs.getObject("tables");
                if (tablesVal instanceof List<?> list) {
                    for (Object o : list) {
                        names.add(o instanceof com.axonbase.value.AxonValue av && av.isString()
                            ? av.asString() : String.valueOf(o));
                    }
                }
            }
        } catch (Exception ignored) {}
        String pat = tableNamePattern != null ? tableNamePattern.replace("%", "").toUpperCase() : null;
        if (pat != null && !pat.isEmpty()) {
            names.removeIf(n -> !n.toUpperCase().contains(pat));
        }
        return names;
    }

    private String db() { try { return connection.getCatalog(); } catch (Exception e) { return "main"; } }
    private String ns() { try { return connection.getSchema(); } catch (Exception e) { return "app"; } }

    private static AxonValue columnRow(String col, String table, String db, String ns, int type, String typeName, int nullable, int ordinal) {
        return AxonValue.object(Map.of(
            "COLUMN_NAME", AxonValue.str(col),
            "TABLE_NAME", AxonValue.str(table),
            "TABLE_CAT", AxonValue.str(db),
            "TABLE_SCHEM", AxonValue.str(ns),
            "DATA_TYPE", AxonValue.num(type),
            "TYPE_NAME", AxonValue.str(typeName),
            "NULLABLE", AxonValue.num(nullable),
            "ORDINAL_POSITION", AxonValue.num(ordinal),
            "COLUMN_SIZE", AxonValue.num(255),
            "IS_NULLABLE", AxonValue.str(nullable == columnNoNulls ? "NO" : "YES")
        ));
    }

    @Override public ResultSet getSchemas() {
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of(
            AxonValue.object(Map.of("TABLE_SCHEM", AxonValue.str(ns())))
        ))));
    }
    @Override public ResultSet getCatalogs() {
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of(
            AxonValue.object(Map.of("TABLE_CAT", AxonValue.str(db())))
        ))));
    }
    @Override public ResultSet getTableTypes() {
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of(
            AxonValue.object(Map.of("TABLE_TYPE", AxonValue.str("TABLE")))
        ))));
    }
    @Override public ResultSet getSchemas(String catalog, String schemaPattern) {
        return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of(
            AxonValue.object(Map.of("TABLE_SCHEM", AxonValue.str(ns())))
        ))));
    }

    @Override public String getDatabaseProductName() { return "AxonBase"; }
    @Override public String getDatabaseProductVersion() { return "0.1.0"; }
    @Override public String getDriverName() { return "AxonBase JDBC Driver"; }
    @Override public String getDriverVersion() { return "0.1.0"; }
    @Override public int getDriverMajorVersion() { return 0; }
    @Override public int getDriverMinorVersion() { return 1; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public String getURL() { return ""; }
    @Override public String getUserName() { return "root"; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return true; }
    @Override public String getIdentifierQuoteString() { return "\""; }
    @Override public String getSQLKeywords() { return ""; }
    @Override public String getNumericFunctions() { return ""; }
    @Override public String getStringFunctions() { return ""; }
    @Override public String getSystemFunctions() { return ""; }
    @Override public String getTimeDateFunctions() { return ""; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int fromType, int toType) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return false; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return false; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsNonNullableColumns() { return false; }
    @Override public boolean supportsMinimumSQLGrammar() { return false; }
    @Override public boolean supportsCoreSQLGrammar() { return false; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return false; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return false; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public String getSchemaTerm() { return "namespace"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getCatalogTerm() { return "database"; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return true; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return true; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return false; }
    @Override public boolean supportsCorrelatedSubqueries() { return true; }
    @Override public boolean supportsUnion() { return false; }
    @Override public boolean supportsUnionAll() { return false; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return false; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return false; }
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 64; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 64; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 64; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 64; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 64; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
    @Override public boolean supportsTransactions() { return true; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level == Connection.TRANSACTION_READ_COMMITTED; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean supportsResultSetType(int type) { return true; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) { return true; }
    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }
    @Override public boolean supportsBatchUpdates() { return false; }
    @Override public boolean supportsSavepoints() { return true; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return false; }
    @Override public boolean supportsResultSetHoldability(int h) { return false; }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getDatabaseMajorVersion() { return 0; }
    @Override public int getDatabaseMinorVersion() { return 1; }
    @Override public int getSQLStateType() { return sqlStateSQL99; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }
    @Override public ResultSet getPseudoColumns(String c, String s, String t, String col) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getFunctions(String c, String s, String f) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getFunctionColumns(String c, String s, String f, String col) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getAttributes(String c, String s, String t, String a) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getSuperTypes(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getSuperTables(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getProcedures(String c, String s, String p) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getProcedureColumns(String c, String s, String p, String col) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getColumnPrivileges(String c, String s, String t, String col) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getTablePrivileges(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getBestRowIdentifier(String c, String s, String t, int scope, boolean nullable) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getVersionColumns(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getPrimaryKeys(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getImportedKeys(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getExportedKeys(String c, String s, String t) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getCrossReference(String pc, String ps, String pt, String fc, String fs, String ft) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getTypeInfo() { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getIndexInfo(String c, String s, String t, boolean u, boolean a) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getUDTs(String c, String s, String t, int[] types) { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public ResultSet getClientInfoProperties() { return JdbcProxy.create(ResultSet.class, new AxonResultSetBackend(AxonValue.array(List.of()))); }
    @Override public <T> T unwrap(Class<T> iface) { return iface.cast(this); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    @Override public Connection getConnection() { return connection; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return true; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return true; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedLow() { return true; }
    @Override public boolean nullsAreSortedHigh() { return false; }
}