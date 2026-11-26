import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.storage.MemoryBackend;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Escritas confirmadas depois de uma falta de quórum chegam aos nós?
 *
 * <p>Sobe três nós num processo, confirma uma escrita, para os dois seguidores, tenta outra (que falha por falta de
 * quórum), traz os seguidores de volta e confirma cinco escritas. Nenhum nó é derrubado à força e não há mistura de
 * versões. Até a 0.10.1, o log não esquece em memória a entrada descartada: o líder passa a confirmar as escritas
 * seguintes sem gravá-las. Usa só a API que já existia na 0.10.1.</p>
 *
 * <pre>
 * javac -cp "&lt;classpath da versão&gt;" -d out scripts/raft-gates/LegacyQuorumLoss.java
 * java -cp "&lt;classpath da versão&gt;:out" LegacyQuorumLoss &lt;diretório vazio&gt;
 * </pre>
 *
 * <p>Cada linha começa com {@code PROVA}. O código de saída é 1 quando uma escrita confirmada não está nos nós,
 * 0 quando todas estão em cinco tentativas.</p>
 */
public final class LegacyQuorumLoss {

    private static final String[] IDS = {"n1", "n2", "n3"};
    private static final int ATTEMPTS = 5;
    private static final int WRITES = 5;

    private LegacyQuorumLoss() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (lost(root.resolve("tentativa-" + attempt), attempt) > 0) {
                System.out.println("PROVA RESULT DEFEITO escritas confirmadas que nenhum nó guarda");
                System.exit(1);
            }
        }
        System.out.println("PROVA RESULT OK todas as escritas confirmadas estão nos nós");
        System.exit(0);
    }

    private static int lost(Path directory, int attempt) throws Exception {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                addresses.add(new InetSocketAddress("127.0.0.1", socket.getLocalPort()));
            }
        }
        ClusterRuntime[] nodes = new ClusterRuntime[IDS.length];
        try {
            for (int i = 0; i < IDS.length; i++) {
                nodes[i] = start(directory, addresses, i);
            }
            await(() -> leader(nodes) >= 0);
            int first = leader(nodes);
            write(nodes[first], IDS[first], "antes");
            for (int i = 0; i < IDS.length; i++) {
                if (i != first) {
                    nodes[i].close();
                    nodes[i] = null;
                }
            }
            try {
                write(nodes[first], IDS[first], "sem-quorum");
                System.out.println("PROVA tentativa=" + attempt + " a escrita sem quórum não falhou");
            } catch (RuntimeException refused) {
                System.out.println("PROVA tentativa=" + attempt + " lider=" + IDS[first]
                    + " escrita sem quórum recusada: " + refused.getClass().getSimpleName());
            }
            for (int i = 0; i < IDS.length; i++) {
                if (nodes[i] == null) {
                    nodes[i] = start(directory, addresses, i);
                }
            }
            Thread.sleep(1_000);
            await(() -> leader(nodes) >= 0);
            int now = leader(nodes);
            List<String> confirmed = new ArrayList<>();
            for (int i = 1; i <= WRITES; i++) {
                String key = "depois-" + i;
                try {
                    long index = write(nodes[now], IDS[now], key);
                    confirmed.add(key);
                    System.out.println("PROVA tentativa=" + attempt + " confirmada=" + key + " indice_devolvido="
                        + index + " ultimo_do_log=" + nodes[now].lastIndex() + " lider=" + IDS[now]);
                } catch (RuntimeException notConfirmed) {
                    System.out.println("PROVA tentativa=" + attempt + " nao_confirmada=" + key + " "
                        + notConfirmed.getClass().getSimpleName());
                }
            }
            Thread.sleep(1_500);
            int missing = 0;
            for (String key : confirmed) {
                List<String> holders = new ArrayList<>();
                for (int i = 0; i < IDS.length; i++) {
                    if (nodes[i].backend().get(key).isPresent()) {
                        holders.add(IDS[i]);
                    }
                }
                if (holders.size() < 2) {
                    missing++;
                }
                System.out.println("PROVA tentativa=" + attempt + " chave=" + key + " guardada_em=" + holders);
            }
            System.out.println("PROVA tentativa=" + attempt + " confirmadas=" + confirmed.size() + " sem_maioria="
                + missing + " lider_mudou=" + (now != first));
            return missing;
        } finally {
            for (ClusterRuntime node : nodes) {
                if (node != null) {
                    node.close();
                }
            }
        }
    }

    private static ClusterRuntime start(Path directory, List<InetSocketAddress> addresses, int index)
            throws Exception {
        List<InetSocketAddress> peers = new ArrayList<>(addresses);
        peers.remove(index);
        long limit = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try {
                return new ClusterRuntime(new ClusterConfig(IDS[index], "g", addresses.get(index), peers),
                    directory.resolve(IDS[index]), new MemoryBackend());
            } catch (Exception busy) {
                // a porta do nó que acabou de parar pode demorar a ficar livre
                if (System.nanoTime() > limit) {
                    throw busy;
                }
                Thread.sleep(100);
            }
        }
    }

    private static int leader(ClusterRuntime[] nodes) {
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] != null && nodes[i].role() == ClusterRole.LEADER) {
                return i;
            }
        }
        return -1;
    }

    private static long write(ClusterRuntime node, String id, String key) {
        return node.confirm(id, new CommittedBatch(key, Map.of(key, key.getBytes(StandardCharsets.UTF_8)), Set.of()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > limit) {
                throw new IllegalStateException("condição não atingida em 30 s");
            }
            Thread.sleep(20);
        }
    }
}
