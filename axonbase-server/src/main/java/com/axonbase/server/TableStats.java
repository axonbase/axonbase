package com.axonbase.server;

import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estatísticas de uma tabela para o painel: contagem sem materializar documentos, campos, índices e
 * campos geoespaciais/vetoriais. A contagem é O(n) sobre chaves (nunca decodifica valores).
 */
final class TableStats {

    /** Quantos documentos olhar para descobrir campos geo em tabelas sem esquema. */
    static final int GEO_SAMPLE = 50;

    private TableStats() {
    }

    static AxonValue compute(Database db, Catalog.TableDef table) {
        List<Object> fields = new ArrayList<>();
        table.fields().values().stream().sorted(Comparator.comparing(Catalog.FieldDef::name))
            .forEach(f -> fields.add(AdminV1Servlet.fieldInfo(f)));
        List<Object> indexes = new ArrayList<>();
        table.indexes().values().stream().sorted(Comparator.comparing(Catalog.IndexDef::name))
            .forEach(i -> indexes.add(AdminV1Servlet.indexInfo(i)));
        return AdminJson.obj("count", count(db, table), "fields", fields, "indexes", indexes,
            "geoFields", geoFields(db, table), "vectorFields", vectorFields(table));
    }

    /** Usa o índice columnar (uma chave por registro) quando existe; senão percorre as chaves dos registros. */
    static long count(Database db, Catalog.TableDef table) {
        String prefix = null;
        for (Catalog.IndexDef i : table.indexes().values()) {
            if (i.columnar() && !i.columns().isEmpty()) {
                prefix = com.axonbase.core.storage.ColumnarIndex.columnPrefix(
                    db.ns(), db.db(), table.name(), i.name(), i.columns().get(0));
                break;
            }
        }
        if (prefix == null) {
            prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table.name() + "\u0000";
        }
        AtomicLong n = new AtomicLong();
        db.records().scanPrefix(prefix, k -> {
            n.incrementAndGet();
            return true;
        });
        return n.get();
    }

    private static List<String> geoFields(Database db, Catalog.TableDef table) {
        Set<String> out = new LinkedHashSet<>();
        for (Catalog.FieldDef f : table.fields().values()) {
            String t = f.type() == null ? "" : f.type().toLowerCase();
            if (t.startsWith("geometry") || t.equals("point") || t.startsWith("geo")) {
                out.add(f.name());
            }
        }
        for (Catalog.IndexDef i : table.indexes().values()) {
            if (i.geo()) {
                out.addAll(i.columns());
            }
        }
        if (!table.schemafull()) {
            sampleGeo(db, table, out);
        }
        return out.stream().sorted().toList();
    }

    private static void sampleGeo(Database db, Catalog.TableDef table, Set<String> out) {
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table.name() + "\u0000";
        AtomicLong seen = new AtomicLong();
        db.records().scanPrefix(prefix, key -> {
            db.records().get(key).ifPresent(raw -> {
                AxonValue doc = AxonJson.decode(raw);
                if (doc != null && doc.isObject()) {
                    doc.asObject().forEach((name, v) -> {
                        if (v != null && v.isObject() && v.asObject().get("type") != null
                            && v.asObject().get("type").isString() && v.asObject().get("coordinates") != null) {
                            out.add(name);
                        }
                    });
                }
            });
            return seen.incrementAndGet() < GEO_SAMPLE;
        });
    }

    private static List<String> vectorFields(Catalog.TableDef table) {
        Set<String> out = new LinkedHashSet<>();
        for (Catalog.IndexDef i : table.indexes().values()) {
            if (i.vector()) {
                out.addAll(i.columns());
            }
        }
        return out.stream().sorted().toList();
    }
}
