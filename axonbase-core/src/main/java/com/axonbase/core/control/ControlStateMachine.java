package com.axonbase.core.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State machine idempotente do catálogo replicado.
 *
 * <p>Aplicar o mesmo comando duas vezes tem o efeito de aplicá-lo uma vez, o que
 * é o que permite ao seguidor reprocessar um batch já visto sem divergir do
 * líder.</p>
 */
public final class ControlStateMachine {

    private final Map<String, ControlCommand> state = new LinkedHashMap<>();

    public synchronized void apply(ControlCommand command) {
        state.put(command.identity(), command);
    }

    /** Remove os comandos que casam com o filtro (DROP TABLE tira a tabela e o que pertence a ela do replay). */
    public synchronized int removeWhere(java.util.function.Predicate<ControlCommand> filter) {
        int before = state.size();
        state.values().removeIf(filter);
        return before - state.size();
    }

    public synchronized ControlSnapshot snapshot() {
        return new ControlSnapshot(new ArrayList<>(state.values()));
    }

    public synchronized void restore(ControlSnapshot snapshot) {
        state.clear();
        snapshot.commands().forEach(this::apply);
    }

    public synchronized int size() {
        return state.size();
    }
}
