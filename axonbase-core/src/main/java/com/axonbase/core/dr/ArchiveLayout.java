package com.axonbase.core.dr;

import java.util.regex.Pattern;

/**
 * Os nomes dos objetos de um cluster no arquivamento:
 *
 * <pre>
 * &lt;prefixo&gt;/&lt;época&gt;/groups/&lt;grupo&gt;/backups/&lt;nó&gt;/files/...
 * &lt;prefixo&gt;/&lt;época&gt;/groups/&lt;grupo&gt;/backups/&lt;nó&gt;/descriptors/&lt;id&gt;-&lt;ms&gt;-&lt;lógico&gt;
 * &lt;prefixo&gt;/&lt;época&gt;/groups/&lt;grupo&gt;/journal/&lt;nó&gt;/seg-*.axj
 * </pre>
 *
 * <p>A época separa histórias diferentes, porque um cluster restaurado reaproveita índices do log. O nível
 * {@code groups} é o ponto de extensão para vários shards.</p>
 *
 * @param prefix prefixo no armazenamento
 * @param epoch  história arquivada
 * @param group  grupo Raft
 */
public record ArchiveLayout(String prefix, String epoch, String group) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public ArchiveLayout {
        require(prefix);
        require(epoch);
        require(group);
    }

    static String require(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("nome inválido no arquivamento: " + name);
        }
        return name;
    }

    private String root() {
        return prefix + "/" + epoch + "/groups/" + group + "/";
    }

    String backupsRoot() {
        return root() + "backups/";
    }

    String backups(String node) {
        return backupsRoot() + require(node) + "/";
    }

    String backupFile(String node, String path) {
        return backups(node) + "files/" + path;
    }

    String descriptors(String node) {
        return backups(node) + "descriptors/";
    }

    String journalRoot() {
        return root() + "journal/";
    }

    String journal(String node) {
        return journalRoot() + require(node) + "/";
    }
}
