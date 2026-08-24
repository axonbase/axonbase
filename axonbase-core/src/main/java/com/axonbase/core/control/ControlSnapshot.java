package com.axonbase.core.control;

import java.util.Comparator;
import java.util.List;

/**
 * Snapshot serializável das definições de catálogo e de identidade.
 *
 * <p>Os comandos vêm em ordem de replay: {@link ControlCommand#rank()} primeiro,
 * depois namespace, banco e nome. Assim, dois nós que partem do mesmo snapshot
 * reconstroem exatamente o mesmo catálogo.</p>
 */
public record ControlSnapshot(List<ControlCommand> commands) {

    private static final Comparator<ControlCommand> REPLAY_ORDER =
        Comparator.comparingInt(ControlCommand::rank)
            .thenComparing(ControlCommand::namespace, Comparator.naturalOrder())
            .thenComparing(ControlCommand::database, Comparator.naturalOrder())
            .thenComparing(ControlCommand::name, Comparator.naturalOrder());

    public ControlSnapshot {
        commands = commands.stream().sorted(REPLAY_ORDER).toList();
    }

    public static ControlSnapshot empty() {
        return new ControlSnapshot(List.of());
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }
}
