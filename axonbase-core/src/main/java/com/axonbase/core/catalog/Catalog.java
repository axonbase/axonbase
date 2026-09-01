package com.axonbase.core.catalog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.axonbase.parser.ast.Expr;

/**
 * Catálogo de definicións dunha base de datos. O catálogo mód reservase
 * en memoria; nunha versión futura podería persistirse no KV como no SurrealDB.
 */
public final class Catalog {

    private final ConcurrentMap<String, TableDef> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AnalyzerDef> analyzers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DataRuleDef> dataRules = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, com.axonbase.core.audit.AiAuditDef> audits = new ConcurrentHashMap<>();

    public Catalog() {
    }

    public void defineAudit(com.axonbase.core.audit.AiAuditDef def) {
        audits.put(def.name(), def);
    }

    public com.axonbase.core.audit.AiAuditDef audit(String name) {
        return audits.get(name);
    }

    public boolean removeAudit(String name) {
        return audits.remove(name) != null;
    }

    public List<com.axonbase.core.audit.AiAuditDef> audits() {
        return List.copyOf(audits.values());
    }

    public void defineTable(TableDef def) {
        tables.put(def.name(), def);
    }

    public boolean removeTable(String name) {
        return tables.remove(name) != null;
    }

    public TableDef table(String name) {
        return tables.get(name);
    }

    public List<TableDef> tables() {
        return List.copyOf(tables.values());
    }

    public void defineAnalyzer(AnalyzerDef def) {
        analyzers.put(def.name(), def);
    }

    public AnalyzerDef analyzer(String name) {
        return analyzers.get(name);
    }

    public List<AnalyzerDef> analyzers() {
        return List.copyOf(analyzers.values());
    }

    /** Definición de táboa: SCHEMAFULL/SCHEMALESS, drop, campos, índices, eventos. */
    public static final class TableDef {
        private final String name;
        private final boolean schemafull;
        private final boolean drop;
        private volatile com.axonbase.parser.ast.Statement.Permissions permissions;
        final ConcurrentMap<String, FieldDef> fields = new ConcurrentHashMap<>();
        final ConcurrentMap<String, IndexDef> indexes = new ConcurrentHashMap<>();
        final ConcurrentMap<String, EventDef> events = new ConcurrentHashMap<>();

        public TableDef(String name, boolean schemafull, boolean drop) {
            this.name = name;
            this.schemafull = schemafull;
            this.drop = drop;
        }
        public void permissions(com.axonbase.parser.ast.Statement.Permissions p) {
            this.permissions = p;
        }
        public com.axonbase.parser.ast.Statement.Permissions permissions() {
            return permissions;
        }

        public String name() {
            return name;
        }

        public boolean schemafull() {
            return schemafull;
        }

        public boolean drop() {
            return drop;
        }

        public ConcurrentMap<String, FieldDef> fields() {
            return fields;
        }

        public ConcurrentMap<String, IndexDef> indexes() {
            return indexes;
        }

        public ConcurrentMap<String, EventDef> events() {
            return events;
        }
    }

    /** Definición dun campo. */
    public static final class FieldDef {
        private final String name;
        private final String type;
        private final boolean readonly;
        private final com.axonbase.parser.ast.Expr assertExpr;
        private final com.axonbase.parser.ast.Expr defaultExpr;
        private final String references;

        public FieldDef(String name, String type, boolean readonly) {
            this(name, type, readonly, null, null, null);
        }

        public FieldDef(String name, String type, boolean readonly,
                        com.axonbase.parser.ast.Expr assertExpr,
                        com.axonbase.parser.ast.Expr defaultExpr) {
            this(name, type, readonly, assertExpr, defaultExpr, null);
        }

        public FieldDef(String name, String type, boolean readonly,
                        com.axonbase.parser.ast.Expr assertExpr,
                        com.axonbase.parser.ast.Expr defaultExpr,
                        String references) {
            this.name = name;
            this.type = type;
            this.readonly = readonly;
            this.assertExpr = assertExpr;
            this.defaultExpr = defaultExpr;
            this.references = references;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public boolean readonly() {
            return readonly;
        }

        public com.axonbase.parser.ast.Expr assertExpr() {
            return assertExpr;
        }

        public com.axonbase.parser.ast.Expr defaultExpr() {
            return defaultExpr;
        }

        public String references() {
            return references;
        }
    }

    /** Definición dun índice. */
    public static final class IndexDef {
        public static final int DEFAULT_HNSW_M = 8;
        public static final int DEFAULT_HNSW_EFC = 32;
        public static final int DEFAULT_HNSW_EFS = 32;

        private final String name;
        private final List<String> columns;
        private final boolean unique;
        private final boolean count;
        private final String searchAnalyzer;
        private final boolean geo;
        private final boolean columnar;
        private final Integer vectorDimension;
        private final String vectorDistance;
        private final int hnswM;
        private final int hnswEfc;
        private final int hnswEfs;

        public IndexDef(String name, List<String> columns, boolean unique, boolean count) {
            this(name, columns, unique, count, null, false, false, null, null);
        }

        public IndexDef(String name, List<String> columns, boolean unique, boolean count,
                        String searchAnalyzer, boolean geo, boolean columnar,
                        Integer vectorDimension, String vectorDistance) {
            this(name, columns, unique, count, searchAnalyzer, geo, columnar,
                vectorDimension, vectorDistance, null, null, null);
        }

        public IndexDef(String name, List<String> columns, boolean unique, boolean count,
                        String searchAnalyzer, boolean geo, boolean columnar,
                        Integer vectorDimension, String vectorDistance,
                        Integer hnswM, Integer hnswEfc, Integer hnswEfs) {
            this.name = name;
            this.columns = List.copyOf(columns);
            this.unique = unique;
            this.count = count;
            this.searchAnalyzer = searchAnalyzer;
            this.geo = geo;
            this.columnar = columnar;
            this.vectorDimension = vectorDimension;
            this.vectorDistance = vectorDistance;
            this.hnswM = hnswM == null ? DEFAULT_HNSW_M : hnswM;
            this.hnswEfc = hnswEfc == null ? DEFAULT_HNSW_EFC : hnswEfc;
            this.hnswEfs = hnswEfs == null ? DEFAULT_HNSW_EFS : hnswEfs;
        }

        public String name() { return name; }
        public List<String> columns() { return columns; }
        public boolean unique() { return unique; }
        public boolean count() { return count; }
        public String searchAnalyzer() { return searchAnalyzer; }
        public boolean search() { return searchAnalyzer != null; }
        public boolean geo() { return geo; }
        public boolean columnar() { return columnar; }
        public boolean vector() { return vectorDimension != null; }
        public Integer vectorDimension() { return vectorDimension; }
        public String vectorDistance() { return vectorDistance; }
        public int hnswM() { return hnswM; }
        public int hnswEfc() { return hnswEfc; }
        public int hnswEfs() { return hnswEfs; }
    }

    /** Definição de uma Data Rule (filtro no nível de linha e coluna). */
    public record DataRuleDef(String name, Expr predicate, List<String> maskPatterns) {
        public DataRuleDef {
            maskPatterns = maskPatterns == null ? List.of() : List.copyOf(maskPatterns);
        }
    }

    public void defineDataRule(DataRuleDef def) {
        dataRules.put(def.name(), def);
    }

    public DataRuleDef dataRule(String name) {
        return dataRules.get(name);
    }

    public boolean removeDataRule(String name) {
        return dataRules.remove(name) != null;
    }

    public List<DataRuleDef> dataRules() {
        return List.copyOf(dataRules.values());
    }

    /** Configuração de normalização para um índice full-text. */
    public record AnalyzerDef(String name, boolean lowercase, List<String> stopwords,
                              boolean stemming) {
        public AnalyzerDef {
            stopwords = List.copyOf(stopwords);
        }
    }

    /**
     * Evento de tabela (DEFINE EVENT ... WHEN ... THEN ...). Guarda a condição e
     * as sentenças a executar quando um registro da tabela muda.
     */
    public static final class EventDef {
        private final String name;
        private final String when;
        private final com.axonbase.parser.ast.Expr whenExpr;
        private final java.util.List<com.axonbase.parser.ast.Statement> then;

        public EventDef(String name, String when) {
            this(name, when, null, java.util.List.of());
        }

        public EventDef(String name, String when, com.axonbase.parser.ast.Expr whenExpr,
                        java.util.List<com.axonbase.parser.ast.Statement> then) {
            this.name = name;
            this.when = when;
            this.whenExpr = whenExpr;
            this.then = then == null ? java.util.List.of() : java.util.List.copyOf(then);
        }

        public String name() {
            return name;
        }

        public String when() {
            return when;
        }

        /** Condição WHEN, ou null se o evento dispara sempre. */
        public com.axonbase.parser.ast.Expr whenExpr() {
            return whenExpr;
        }

        /** Sentenças THEN a executar quando a condição é verdadeira. */
        public java.util.List<com.axonbase.parser.ast.Statement> then() {
            return then;
        }
    }
}
