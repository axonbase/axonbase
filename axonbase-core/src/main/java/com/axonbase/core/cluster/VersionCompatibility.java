package com.axonbase.core.cluster;

import java.math.BigInteger;
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
 *
 * <p>Com um membro 0.10.x não há atualização nó a nó: enquanto ele participa do quórum, os defeitos dele podem perder
 * escritas confirmadas. A troca é com os três nós parados.</p>
 */
public final class VersionCompatibility {

    /** A versão mais antiga que ainda conversa com este build. */
    public static final String MINIMUM_PEER = "0.10.1";

    /** Grupos: maior, menor, correção e pré-lançamento. O metadado de build, depois do {@code +}, é ignorado. */
    private static final Pattern VERSION =
        Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([^+]*))?(?:\\+.*)?");

    private static final Pattern RUN = Pattern.compile("\\d+|\\D+");

    /** @param problems vazio quando a atualização é permitida */
    public record Check(boolean allowed, List<String> problems) {
    }

    private record Version(int major, int minor, int patch, String pre) implements Comparable<Version> {
        boolean needsFullStop() {
            return major == 0 && minor == 10;
        }

        @Override
        public int compareTo(Version other) {
            if (major != other.major) {
                return Integer.compare(major, other.major);
            }
            if (minor != other.minor) {
                return Integer.compare(minor, other.minor);
            }
            return patch != other.patch ? Integer.compare(patch, other.patch) : comparePre(pre, other.pre);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + pre);
        }
    }

    private VersionCompatibility() {
    }

    /** A versão final vem depois dos pré-lançamentos dela; entre eles, os números contam como números (rc2 antes de rc10). */
    private static int comparePre(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(a.isEmpty(), b.isEmpty());
        }
        Matcher left = RUN.matcher(a);
        Matcher right = RUN.matcher(b);
        while (true) {
            boolean hasLeft = left.find();
            boolean hasRight = right.find();
            if (!hasLeft || !hasRight) {
                return Boolean.compare(hasLeft, hasRight);
            }
            String x = left.group();
            String y = right.group();
            boolean numbers = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0));
            int order = numbers ? new BigInteger(x).compareTo(new BigInteger(y)) : x.compareToIgnoreCase(y);
            if (order != 0) {
                return order;
            }
        }
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
            List<String> fullStop = new ArrayList<>();
            current.forEach((node, version) -> {
                if (version.needsFullStop()) {
                    fullStop.add(node + " na " + version);
                } else if (version.compareTo(minimum) < 0) {
                    problems.add("o membro " + node + " está na " + version + ", anterior à mínima " + MINIMUM_PEER);
                }
                if (wanted.compareTo(version) < 0) {
                    problems.add("a " + wanted + " é anterior à " + version + " do membro " + node
                        + ": não há volta para versão antiga no mesmo diretório");
                }
            });
            if (!fullStop.isEmpty()) {
                problems.add("não há atualização nó a nó a partir da 0.10.x (" + String.join(", ", fullStop)
                    + "): pare os três nós, atualize todos para a mesma versão e só então suba o cluster (veja "
                    + "docs/operacao-e-resiliencia.md)");
            }
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
                Integer.parseInt(matcher.group(3)), matcher.group(4) == null ? "" : matcher.group(4));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }
}
