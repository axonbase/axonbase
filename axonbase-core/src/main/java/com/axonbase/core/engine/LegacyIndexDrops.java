package com.axonbase.core.engine;

import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.partition.PartitionKeys;
import com.axonbase.core.storage.ColumnarIndex;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bases gravadas até a 1.0.0-rc1 guardam o {@code DROP INDEX} no plano de controle, ao lado do {@code DEFINE INDEX}
 * que ele deveria apagar. Os dois têm identidades diferentes e a ordem em que aconteceram se perdeu, então reexecutar
 * o par depende só dos nomes: o DROP antes do DEFINE impede a base de abrir.
 *
 * <p>Aqui o DROP herdado sai do plano de controle e o que o índice deixou no armazenamento decide o DEFINE. O DROP
 * apagava todas as entradas, então um índice que ainda as tem foi recriado depois dele e fica; um que não as tem foi
 * apagado e sai junto. Um UNIQUE sem nenhuma linha que precisasse de entrada fica, porque perder a restrição aceitaria
 * duplicatas. Um índice comum não grava entrada alguma: sai (basta recriá-lo).</p>
 */
final class LegacyIndexDrops {

    private static final System.Logger LOG = System.getLogger(LegacyIndexDrops.class.getName());
    private static final String DROP = "DROP INDEX ";

    private LegacyIndexDrops() {
    }

    /** Os comandos sem os DROP herdados e sem as definições que eles já tinham apagado. */
    static List<ControlCommand> clean(List<ControlCommand> commands, KvBackend kv) {
        List<ControlCommand> drops = commands.stream().filter(LegacyIndexDrops::isDrop).toList();
        if (drops.isEmpty()) {
            return commands;
        }
        Set<ControlCommand> removed = new HashSet<>(drops);
        for (ControlCommand drop : drops) {
            String name = drop.definition().substring(DROP.length()).trim();
            for (ControlCommand command : commands) {
                Statement.DefineIndex index = definitionOf(command, drop, name);
                if (index != null && !survived(kv, command, index, commands)) {
                    removed.add(command);
                    LOG.log(System.Logger.Level.WARNING, "índice ''{0}'' da tabela ''{1}'' ({2}/{3}) tinha um DROP "
                        + "INDEX gravado e nenhuma entrada: ficou apagado", name, index.table(), drop.namespace(),
                        drop.database());
                }
            }
        }
        List<ControlCommand> kept = new ArrayList<>(commands);
        kept.removeIf(removed::contains);
        return kept;
    }

    private static boolean isDrop(ControlCommand command) {
        return command.kind() == ControlCommand.Kind.INDEX
            && command.definition().regionMatches(true, 0, DROP, 0, DROP.length());
    }

    /** O {@code DEFINE INDEX} do comando, quando é o índice de que o DROP fala; senão {@code null}. */
    private static Statement.DefineIndex definitionOf(ControlCommand command, ControlCommand drop, String name) {
        if (command.kind() != ControlCommand.Kind.INDEX || isDrop(command)
            || !command.namespace().equals(drop.namespace()) || !command.database().equals(drop.database())
            || !command.name().endsWith(":" + name)) {
            return null;
        }
        List<Statement> statements = AxonQl.parse(command.definition()).statements();
        return statements.size() == 1 && statements.get(0) instanceof Statement.DefineIndex index
            && index.name().equals(name) ? index : null;
    }

    private static boolean survived(KvBackend kv, ControlCommand command, Statement.DefineIndex index,
                                    List<ControlCommand> commands) {
        String ns = command.namespace();
        String db = command.database();
        String scope = ns + "|" + db + "|" + index.table() + "|" + index.name();
        // construção interrompida, ou índice GEO atual: o DROP apagava o marcador
        if (kv.get("IXB|" + scope).isPresent()) {
            return true;
        }
        if (index.columnar()) {
            return kv.get(ColumnarIndex.readyKey(ns, db, index.table(), index.name())).isPresent()
                || any(kv, ColumnarIndex.indexPrefix(ns, db, index.table(), index.name()));
        }
        if (index.vectorDimension() != null) {
            return kv.get("VHT|" + scope).isPresent() || any(kv, "VH|" + scope + "|") || any(kv, "VX|" + scope + "|");
        }
        if (index.searchAnalyzer() != null) {
            return any(kv, "FT|" + scope + "|") || any(kv, "FTLEN|" + scope + "|");
        }
        return index.unique() && uniqueSurvived(kv, command, index, commands);
    }

    private static boolean uniqueSurvived(KvBackend kv, ControlCommand command, Statement.DefineIndex index,
                                          List<ControlCommand> commands) {
        List<String> columns = index.columns();
        // dois UNIQUE na mesma coluna dividem as entradas: o outro continua garantindo a restrição
        if (columns.size() == 1 && commands.stream().anyMatch(other -> sameUnique(other, command, index))) {
            return false;
        }
        String database = PartitionKeys.dbPrefix(command.namespace(), command.database());
        String table = PartitionKeys.tablePrefix(command.namespace(), command.database(), index.table());
        String entry = "!uidx\u0000" + (columns.size() == 1 ? columns.get(0) : index.name()) + "\u0000";
        if (any(kv, table + entry)) {
            return true;
        }
        boolean[] partitionEntry = {false};
        kv.scanPrefix(table + PartitionKeys.MARK, key -> {
            partitionEntry[0] = key.contains("\u0000" + entry);
            return !partitionEntry[0];
        });
        if (partitionEntry[0]) {
            return true;
        }
        // sem entradas: se havia linha que precisava de uma, o índice foi apagado
        boolean[] needed = {false};
        kv.scanPrefix(table, key -> {
            if (PartitionKeys.record(key.substring(database.length())) != null) {
                needed[0] = kv.get(key).map(raw -> hasAll(AxonJson.decode(raw), columns)).orElse(false);
            }
            return !needed[0];
        });
        return !needed[0];
    }

    private static boolean sameUnique(ControlCommand other, ControlCommand command, Statement.DefineIndex index) {
        if (other == command || other.kind() != ControlCommand.Kind.INDEX || isDrop(other)
            || !other.namespace().equals(command.namespace()) || !other.database().equals(command.database())) {
            return false;
        }
        List<Statement> statements = AxonQl.parse(other.definition()).statements();
        return statements.size() == 1 && statements.get(0) instanceof Statement.DefineIndex candidate
            && candidate.unique() && candidate.table().equals(index.table())
            && candidate.columns().equals(index.columns());
    }

    private static boolean hasAll(AxonValue record, List<String> columns) {
        if (!record.isObject()) {
            return false;
        }
        return columns.stream().allMatch(column -> {
            AxonValue value = record.asObject().get(column);
            return value != null && !value.isNull() && !value.isNone();
        });
    }

    private static boolean any(KvBackend kv, String prefix) {
        boolean[] found = {false};
        kv.scanPrefix(prefix, key -> {
            found[0] = true;
            return false;
        });
        return found[0];
    }
}
