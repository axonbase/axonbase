import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Um nó de cluster para o teste de versões misturadas. Usa só a API que já existia na 0.10.1, então o mesmo
 * fonte é compilado contra as duas versões. Argumentos: id, diretório, porta, portas dos outros membros.
 */
public final class MixedVersionNode {

    private MixedVersionNode() {
    }

    public static void main(String[] args) throws Exception {
        String id = args[0];
        Path dir = Path.of(args[1]);
        InetSocketAddress self = new InetSocketAddress("127.0.0.1", Integer.parseInt(args[2]));
        List<InetSocketAddress> peers = new ArrayList<>();
        for (String port : args[3].split(",")) {
            peers.add(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)));
        }
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString());
             ClusterRuntime node = new ClusterRuntime(new ClusterConfig(id, "g", self, peers),
                 dir.resolve("raft"), backend)) {
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicBoolean running = new AtomicBoolean(true);
            String life = Long.toString(ProcessHandle.current().pid());
            Thread.ofPlatform().daemon().start(() -> {
                long sequence = 0;
                while (running.get()) {
                    if (!writing.get() || node.role() != ClusterRole.LEADER) {
                        sleep(10);
                        continue;
                    }
                    String key = "k-" + id + "-" + life + "-" + sequence++;
                    try {
                        long returned = node.confirm(id, new CommittedBatch(key,
                            Map.of(key, key.getBytes(StandardCharsets.UTF_8)), Set.of()));
                        // diagnóstico: onde o líder estava quando confirmou
                        say("ACK " + key + " " + returned + " " + node.commitIndex() + " " + node.lastIndex() + " "
                            + node.status().term());
                        sleep(5);
                    } catch (RuntimeException notConfirmed) {
                        sleep(20);
                    }
                }
            });
            Thread.ofPlatform().daemon().start(() -> {
                while (running.get()) {
                    say("STATE " + node.role() + " " + node.commitIndex() + " " + node.lastIndex() + " "
                        + node.status().term());
                    sleep(100);
                }
            });
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line = in.readLine(); line != null && !line.equals("exit"); line = in.readLine()) {
                if (line.equals("stop")) {
                    writing.set(false);
                } else if (line.equals("go")) {
                    writing.set(true);
                } else if (line.startsWith("check ")) {
                    check(backend, Path.of(line.substring(6)));
                }
            }
            running.set(false);
        }
        System.exit(0);
    }

    /** Confere as chaves confirmadas (uma por linha) e imprime um resumo do conteúdo. */
    private static void check(RocksDbBackend backend, Path acknowledged) {
        try {
            List<String> missing = java.nio.file.Files.readAllLines(acknowledged).stream()
                .filter(key -> backend.get(key).isEmpty()).toList();
            missing.forEach(key -> say("LOSTKEY " + key));
            long lost = missing.size();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long keys = 0;
            for (String key : backend.keysWithPrefix("k-").stream().sorted().toList()) {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update(backend.get(key).orElseThrow());
                keys++;
            }
            say("CHECK " + lost + " " + keys + " " + HexFormat.of().formatHex(digest.digest()));
        } catch (Exception e) {
            say("CHECKFAILED " + e);
        }
    }

    private static synchronized void say(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
