package com.axonbase.core.partition.cold;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bloqueios de retenção: documentos no próprio armazenamento frio que listam objetos que não podem ser apagados.
 * Cada backup que referencia objetos frios mantém um; cada motor em uso publica o seu.
 */
public final class ColdHolds {

    private static final String PREFIX = "holds/";
    private static final String SUFFIX = ".json";
    /** Validade "sem prazo": o bloqueio fica até ser solto. */
    public static final long NEVER = Long.MAX_VALUE;

    private final ColdStore store;

    public ColdHolds(ColdStore store) {
        this.store = store;
    }

    /** Cria ou substitui o bloqueio {@code name}, válido até {@code expiresAt} (milissegundos). */
    public void put(String name, Collection<String> objects, long expiresAt) throws IOException {
        Map<String, AxonValue> document = new LinkedHashMap<>();
        document.put("expiresAt", AxonValue.num(expiresAt));
        document.put("objects", AxonValue.array(new TreeSet<>(objects).stream().map(AxonValue::str).toList()));
        store.write(key(name), AxonJson.encode(AxonValue.object(document)));
    }

    public void release(String name) throws IOException {
        store.delete(key(name));
    }

    /** Os nomes dos bloqueios que começam com {@code prefix}. */
    public List<String> names(String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        for (String key : store.list(PREFIX + prefix)) {
            if (key.endsWith(SUFFIX)) {
                out.add(key.substring(PREFIX.length(), key.length() - SUFFIX.length()));
            }
        }
        return out;
    }

    /**
     * Os objetos presos por algum bloqueio válido; os vencidos são apagados. Um bloqueio ilegível interrompe: sem
     * saber o que ele prende, nada pode ser apagado.
     */
    public Set<String> held(long now) throws IOException {
        Set<String> held = new TreeSet<>();
        for (String name : names("")) {
            var raw = store.read(key(name));
            if (raw.isEmpty()) {
                continue;
            }
            Map<String, AxonValue> document;
            try {
                document = AxonJson.decode(raw.get()).asObject();
            } catch (RuntimeException unreadable) {
                throw new IOException("bloqueio ilegível: " + name, unreadable);
            }
            if (document.get("expiresAt").asLong() <= now) {
                release(name);
                continue;
            }
            document.get("objects").asArray().forEach(v -> held.add(v.asString()));
        }
        return held;
    }

    private static String key(String name) {
        return PREFIX + name + SUFFIX;
    }
}
