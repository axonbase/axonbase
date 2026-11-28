package com.axonbase.core.partition;

import com.axonbase.core.catalog.Database;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.cold.ColdHolds;
import com.axonbase.core.partition.cold.ColdStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Apaga objetos frios que ninguém mais usa. Um objeto só sai quando nenhum descritor desta base o cita, nenhum
 * bloqueio de retenção o prende e ele está órfão há pelo menos a carência (que cobre a restauração a um instante
 * anterior à remoção da partição).
 */
public final class ColdGarbage {

    static final String OBJECTS = "parts/";
    private static final String ORPHANS = "orphans/";
    private static final long MIN_ENGINE_HOLD_MS = 3_600_000L;

    private ColdGarbage() {
    }

    /** Os objetos citados pelos descritores desta base. */
    public static Set<String> referenced(Datastore ds) {
        Set<String> objects = new TreeSet<>();
        for (Database db : ds.allDatabases()) {
            String prefix = PartitionKeys.DESCRIPTORS + db.ns() + "|" + db.db() + "|";
            db.records().scanEntries(prefix, (key, value) -> {
                String object = PartitionDescriptor.decode(value).object();
                if (object != null) {
                    objects.add(object);
                }
                return true;
            });
        }
        return objects;
    }

    /** Publica o bloqueio deste motor e faz uma passada de coleta. @return quantos objetos foram apagados */
    static int collect(Datastore ds, ColdTier tier, long now) throws IOException {
        ColdStore store = tier.store();
        ColdHolds holds = tier.holds();
        long grace = tier.settings().grace().toMillis();
        // a lista vem antes das referências: um objeto enviado entre as duas não é candidato nesta passada
        var candidates = store.list(OBJECTS);
        Set<String> live = referenced(ds);
        // o bloqueio do motor protege os objetos da base viva de outro motor que use o mesmo armazenamento
        long holdFor = Math.max(MIN_ENGINE_HOLD_MS, Math.max(grace, tier.settings().gcInterval().toMillis() * 2));
        holds.put("engine-" + tier.engineId(), live, now + holdFor);
        Set<String> kept = new HashSet<>(holds.held(now));
        kept.addAll(live);
        int deleted = 0;
        for (String object : candidates) {
            String marker = ORPHANS + object;
            if (kept.contains(object)) {
                store.delete(marker);
                continue;
            }
            var since = store.read(marker);
            if (since.isEmpty()) {
                store.write(marker, Long.toString(now).getBytes(StandardCharsets.US_ASCII));
            } else if (now - Long.parseLong(new String(since.get(), StandardCharsets.US_ASCII).trim()) >= grace) {
                store.delete(object);
                store.delete(marker);
                deleted++;
            }
        }
        return deleted;
    }
}
