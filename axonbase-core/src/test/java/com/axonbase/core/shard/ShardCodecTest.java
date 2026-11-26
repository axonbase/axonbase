package com.axonbase.core.shard;

import com.axonbase.common.AxonError;
import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: formato das mensagens e tradução dos erros")
class ShardCodecTest {

    @Test
    void todosOsTiposVoltamIguais() {
        Map<String, AxonValue> object = new LinkedHashMap<>();
        object.put("none", AxonValue.none());
        object.put("null", AxonValue.nul());
        object.put("bool", AxonValue.bool(true));
        object.put("long", AxonValue.num(Long.MIN_VALUE));
        object.put("double", AxonValue.num(0.1));
        object.put("decimal", AxonValue.num(new BigDecimal("123456789012345678901234567890.5000")));
        object.put("texto", AxonValue.str("ção\u0000x"));
        object.put("duracao", AxonValue.duration(90_000));
        object.put("data", AxonValue.datetime(Instant.parse("2026-11-26T14:32:10.123456789Z")));
        object.put("uuid", AxonValue.uuid(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")));
        object.put("lista", AxonValue.array(List.of(AxonValue.num(1), AxonValue.str("a"))));
        object.put("conjunto", AxonValue.set(List.of(AxonValue.num(2))));
        object.put("bytes", AxonValue.bytes(new byte[] {1, 2, 3}));
        object.put("registro", AxonValue.record("pedido", AxonValue.num(5)));
        object.put("tabela", AxonValue.table("pedido"));

        ShardReply read = ShardCodec.decodeReply(
            ShardCodec.encode(new ShardReply(AxonValue.object(object), new HlcTimestamp(7, 3))));

        assertEquals(new HlcTimestamp(7, 3), read.stamp());
        Map<String, AxonValue> out = read.value().asObject();
        assertEquals(List.copyOf(object.keySet()), List.copyOf(out.keySet()));
        object.forEach((key, value) -> {
            assertEquals(value.type(), out.get(key).type(), key);
            if (value.isBytes()) {
                assertArrayEquals((byte[]) value.raw(), (byte[]) out.get(key).raw());
            } else {
                assertEquals(value.toString(), out.get(key).toString(), key);
            }
        });
        assertEquals(BigDecimal.class, out.get("decimal").raw().getClass());
        assertEquals(Double.class, out.get("double").raw().getClass());
        assertEquals(object.get("registro"), out.get("registro"));
    }

    @Test
    void requisicaoVoltaInteiraComACredencialDoCabecalho() {
        ShardRequest sent = new ShardRequest("n", null, "SELECT * FROM t", Map.of("a", AxonValue.num(1)), true, true,
            new SlotFilter("t", "k", List.of(new SlotRange(0, 9))), new HlcTimestamp(10, 1), "Bearer nao-vai-no-corpo");

        byte[] body = ShardCodec.encode(sent);
        ShardRequest read = ShardCodec.decodeRequest(body, "Bearer outro");

        assertEquals(sent.withAuthorization("Bearer outro"), read);
        assertTrue(!new String(body, java.nio.charset.StandardCharsets.ISO_8859_1).contains("nao-vai-no-corpo"));
    }

    @Test
    void mensagemTruncadaOuForjadaERecusada() {
        byte[] body = ShardCodec.encode(ShardRequest.of("n", "d", "SELECT * FROM t"));

        assertThrows(IllegalArgumentException.class,
            () -> ShardCodec.decodeRequest(java.util.Arrays.copyOf(body, body.length - 3), null));
        assertThrows(IllegalArgumentException.class, () -> ShardCodec.decodeReply(new byte[] {9}));
        // uma lista que declara bilhões de itens não reserva memória
        assertThrows(IllegalArgumentException.class,
            () -> ShardCodec.decodeReply(new byte[] {1, 10, 127, -1, -1, -1}));
    }

    private static RuntimeException roundTrip(RuntimeException error) {
        ShardHttp.Failure failure = ShardHttp.failure(error);
        return ShardHttp.error("s0", failure.status(), failure.body());
    }

    @Test
    void errosAtravessamARedeSemPerderOSignificado() {
        NotLeaderException redirected = (NotLeaderException) roundTrip(new NotLeaderException("n2", "10.0.0.2:9000"));
        assertEquals("10.0.0.2:9000", redirected.leaderAddress());
        assertEquals(CommitOutcomeUnknownException.class,
            roundTrip(new CommitOutcomeUnknownException("g")).getClass());
        assertEquals(QuorumUnavailableException.class, roundTrip(new QuorumUnavailableException("g")).getClass());
        assertEquals(VersionConflictException.class, roundTrip(new VersionConflictException("x", "k")).getClass());
        ShardException moving = (ShardException) roundTrip(new ShardException(ShardException.Code.MOVING, "movendo"));
        assertEquals(ShardException.Code.MOVING, moving.code());
        AxonError denied = (AxonError) roundTrip(new AxonError(-32002, "sem papel"));
        assertEquals(-32002, denied.code());
        assertEquals("sem papel", denied.getMessage());
        assertEquals(403, ShardHttp.failure(new AxonError(-32002, "x")).status());
        assertTrue(ShardHttp.error("s0", 502, "<html>").getMessage().contains("502"));
    }
}
