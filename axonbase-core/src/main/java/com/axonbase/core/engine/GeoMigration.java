package com.axonbase.core.engine;

import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Passagem do índice geohash para o H3. Nenhum índice antigo ou atrasado atende consulta: ele volta ao estado "em
 * construção" (a consulta varre a tabela, com o resultado certo) e é refeito em segundo plano pelo
 * {@link IndexBuilder}.
 *
 * <p>Isso acontece em dois momentos: ao recarregar o catálogo (abrir a base, restaurar um backup, seguidor aplicando
 * DDL) e quando um lote replicado traz escritas no formato antigo, de um líder que ainda não foi atualizado.</p>
 */
final class GeoMigration {

    private GeoMigration() {
    }

    /**
     * No replay do catálogo: o índice precisa ser refeito. Carrega a biblioteca nativa aqui, para que a falta dela
     * apareça na abertura da base, e não como uma construção abortada que derruba o índice.
     */
    static boolean mustRebuild(Database db, String table, String index) {
        H3Cells.core();
        return new GeoIndex(db.ns(), db.db(), table, index).mustRebuild(db.records());
    }

    /** Depois de um lote replicado: entradas geohash nele deixam o índice H3 daquela tabela atrasado. */
    static void onReplicated(Datastore ds, Set<String> writtenKeys) {
        Set<List<String>> owners = new LinkedHashSet<>();
        for (String key : writtenKeys) {
            List<String> owner = GeoIndex.legacyEntryOwner(key);
            if (owner != null) {
                owners.add(owner);
            }
        }
        for (List<String> owner : owners) {
            invalidate(ds, owner.get(0), owner.get(1), owner.get(2), owner.get(3));
        }
    }

    private static void invalidate(Datastore ds, String ns, String dbName, String table, String name) {
        Database db = ds.findDatabase(ns, dbName);
        Catalog.TableDef def = db == null ? null : db.catalog().table(table);
        Catalog.IndexDef index = def == null ? null : def.indexes().get(name);
        if (index == null || !index.geo()) {
            return;
        }
        // sem trava: esta é a thread do consenso, e um commit pode segurar o indexLock esperando por ela
        if (!index.ready()) {
            return; // a construção em andamento confere as entradas antigas depois de marcar o índice pronto
        }
        index.markBuilding();
        db.bumpIndexEpoch();
        IndexBuilder.restartAsync(ds, ns, dbName, table, name);
    }
}
