package com.axonbase.core.pl;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadados que o texto AxonQL não carrega (versão, datas e dono). Vão numa linha de comentário no começo da
 * definição guardada no plano de controle ({@code -- axonpl {json}}); o lexer a ignora e o replay a lê.
 */
public final class ProgramMeta {

    private static final String PREFIX = "-- axonpl ";
    private static final ThreadLocal<ProgramMeta> REPLAY = new ThreadLocal<>();

    private final long version;
    private final long created;
    private final long updated;
    private final AxonValue owner;

    public ProgramMeta(long version, long created, long updated, AxonValue owner) {
        this.version = version;
        this.created = created;
        this.updated = updated;
        this.owner = owner;
    }

    public long version() {
        return version;
    }

    public long created() {
        return created;
    }

    public long updated() {
        return updated;
    }

    public AxonValue owner() {
        return owner;
    }

    /** Linha de cabeçalho a gravar antes do texto da definição. */
    String header() {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        m.put("version", AxonValue.num(version));
        m.put("created", AxonValue.num(created));
        m.put("updated", AxonValue.num(updated));
        if (owner != null && owner.isObject()) {
            m.put("owner", owner);
        }
        return PREFIX + AxonJson.write(AxonValue.object(m)).replace('\n', ' ') + "\n";
    }

    /** Lê o cabeçalho de uma definição, ou {@code null} se não houver (ou estiver ilegível). */
    public static ProgramMeta parse(String definition) {
        if (definition == null || !definition.startsWith(PREFIX)) {
            return null;
        }
        int eol = definition.indexOf('\n');
        String json = definition.substring(PREFIX.length(), eol < 0 ? definition.length() : eol);
        try {
            Map<String, AxonValue> m = AxonJson.parseDocument(json).asObject();
            return new ProgramMeta(m.get("version").asLong(), m.get("created").asLong(), m.get("updated").asLong(),
                m.get("owner"));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Marca que a thread está reexecutando uma definição do plano de controle (usado pelo Datastore). */
    public static void beginReplay(String definition) {
        ProgramMeta meta = parse(definition);
        if (meta == null) {
            REPLAY.remove();
        } else {
            REPLAY.set(meta);
        }
    }

    public static void endReplay() {
        REPLAY.remove();
    }

    static ProgramMeta replaying() {
        return REPLAY.get();
    }
}
