package com.axonbase.core.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * As regras para atualizar um nó de cada vez: não há volta para uma versão anterior no mesmo diretório, nenhum
 * membro pode ser mais antigo que {@link #MINIMUM_PEER} e o cluster não pode passar a ter três versões.
 */
public final class VersionCompatibility {

    /** A versão mais antiga que ainda conversa com este build. */
    public static final String MINIMUM_PEER = "0.10.1";

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)([-+].*)?");

    /** @param problems vazio quando a atualização é permitida */
    public record Check(boolean allowed, List<String> problems) {
    }

    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            if (major != other.major) {
                return Integer.compare(major, other.major);
            }
            return minor != other.minor ? Integer.compare(minor, other.minor) : Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private VersionCompatibility() {
    }

    /**
     * @param members versão atual de cada membro, por identificador
     * @param target  versão para a qual o próximo nó vai
     */
    public static Check check(Map<String, String> members, String target) {
        List<String> problems = new ArrayList<>();
        Version wanted = parse(target);
        if (wanted == null) {
            problems.add("versão de destino inválida: " + target);
        }
        if (members.isEmpty()) {
            problems.add("nenhum membro informado");
        }
        Map<String, Version> current = new LinkedHashMap<>();
        members.forEach((node, raw) -> {
            Version version = parse(raw);
            if (version == null) {
                problems.add("versão inválida no membro " + node + ": " + raw);
            } else {
                current.put(node, version);
            }
        });
        if (wanted != null) {
            Version minimum = parse(MINIMUM_PEER);
            current.forEach((node, version) -> {
                if (version.compareTo(minimum) < 0) {
                    problems.add("o membro " + node + " está na " + version + ", anterior à mínima " + MINIMUM_PEER);
                }
                if (wanted.compareTo(version) < 0) {
                    problems.add("a " + wanted + " é anterior à " + version + " do membro " + node
                        + ": não há volta para versão antiga no mesmo diretório");
                }
            });
            Set<Version> after = new LinkedHashSet<>(current.values());
            after.add(wanted);
            if (after.size() > 2) {
                problems.add("o cluster ficaria com três versões " + after + "; termine a atualização em curso antes");
            }
        }
        return new Check(problems.isEmpty(), List.copyOf(problems));
    }

    private static Version parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }
}
