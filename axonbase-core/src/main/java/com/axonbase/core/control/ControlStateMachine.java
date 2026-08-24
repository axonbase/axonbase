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
