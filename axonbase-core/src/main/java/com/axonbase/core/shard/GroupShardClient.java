package com.axonbase.core.shard;

import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.cluster.StaleReadException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Fala com um grupo Raft: procura o líder entre os membros e repete, até o prazo, só quando é certo que nada foi
 * executado (não é o líder, nó inalcançável, sem maioria antes de gravar). Um desfecho desconhecido nunca é repetido.
 */
public final class GroupShardClient implements ShardClient {

    private static final long FIRST_PAUSE_MILLIS = 5;
    private static final long LONGEST_PAUSE_MILLIS = 100;

    private final String shard;
    private final Supplier<List<ShardMember>> members;
    private final Duration deadline;
    /** O membro que atendeu por último: a próxima requisição começa por ele. */
    private final AtomicInteger preferred = new AtomicInteger();

    public GroupShardClient(String shard, Supplier<List<ShardMember>> members, Duration deadline) {
        this.shard = shard;
        this.members = members;
        this.deadline = deadline;
    }

    @Override
    public ShardReply execute(ShardRequest request) {
        long limit = System.nanoTime() + deadline.toNanos();
        long pause = FIRST_PAUSE_MILLIS;
        RuntimeException last = null;
        while (true) {
            List<ShardMember> current = members.get();
            for (int attempt = 0; attempt < current.size(); attempt++) {
                if (last != null && System.nanoTime() - limit >= 0) {
                    break;
                }
                // a troca compara o contador, não a posição: a lista muda de tamanho quando um nó cai
                int turn = preferred.get();
                try {
                    return current.get(Math.floorMod(turn, current.size())).handle(request);
                } catch (CommitOutcomeUnknownException unknown) {
                    throw unknown;
                } catch (NotLeaderException | ShardUnreachableException | QuorumUnavailableException
                         | StaleReadException retry) {
                    last = retry;
                    preferred.compareAndSet(turn, turn + 1);
                }
            }
            if (System.nanoTime() - limit >= 0) {
                throw new ShardException(ShardException.Code.UNAVAILABLE, ShardMessages.get("shard_unavailable",
                    shard, last == null ? "-" : last.getMessage()), last);
            }
            pause(pause);
            pause = Math.min(pause * 2, LONGEST_PAUSE_MILLIS);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardException(ShardException.Code.UNAVAILABLE,
                ShardMessages.get("shard_unavailable", shard, "interrompido"), e);
        }
    }
}
