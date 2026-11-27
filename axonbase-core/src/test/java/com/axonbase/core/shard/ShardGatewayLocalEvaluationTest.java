package com.axonbase.core.shard;

import com.axonbase.common.AxonError;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Shards: o que o gateway calcula exige credencial e tem limite de tamanho")
class ShardGatewayLocalEvaluationTest {

    /** Um gateway sobre shards que, como um nó com autenticação, recusam quem não traz credencial. */
    private static ShardGateway guarded(GatewayFixture fixture) {
        return new ShardGateway(fixture.store, info -> request -> {
            if (request.authorization() == null) {
                throw new AxonError(-32002, "autenticação obrigatória");
            }
            return fixture.clients.get(info.id()).execute(request);
        }, ShardGateway.Options.defaults().withMapTtl(Duration.ZERO), HybridClock.system());
    }

    private static GatewaySession anonymous() {
        return new GatewaySession("n", "d");
    }

    private static GatewaySession authenticated() {
        return new GatewaySession("n", "d", "Bearer cliente");
    }

    @Test
    void semCredencialOGatewayNaoCalculaNada() {
        try (GatewayFixture fixture = GatewayFixture.memory(2); ShardGateway gateway = guarded(fixture)) {
            for (String script : List.of("LET $x = string::repeat('a', 1000)", "LET $x = 1 + 1")) {
                AxonError refused = assertThrows(AxonError.class,
                    () -> gateway.execute(anonymous(), script, null), script);
                assertEquals(-32002, refused.code(), script);
            }
        }
    }

    @Test
    void umValorGrandeDemaisERecusadoAntesDeSerCriado() {
        try (GatewayFixture fixture = GatewayFixture.memory(2); ShardGateway gateway = guarded(fixture)) {
            String many = String.join(", ", Collections.nCopies(100, "$a"));
            for (String script : List.of("LET $x = string::repeat('a', 70000000)",
                "LET $x = string::pad_end('a', 70000000)",
                "LET $a = string::repeat('a', 1000000); LET $b = string::concat(" + many + ")",
                "LET $a = string::repeat('a', 1000000); LET $b = [" + many + "]")) {
                ShardException refused = assertThrows(ShardException.class,
                    () -> gateway.execute(authenticated(), script, null), script);
                assertEquals(ShardException.Code.REFUSED, refused.code(), script);
            }
        }
    }

    @Test
    void comCredencialOCalculoComumContinuaNoGateway() {
        try (GatewayFixture fixture = GatewayFixture.memory(2); ShardGateway gateway = guarded(fixture)) {
            AxonValue result = gateway.execute(authenticated(),
                "LET $a = string::repeat('ab', 3); LET $b = [$a, 1 + 1]; RETURN $b", null);

            assertEquals(List.of(AxonValue.str("ababab"), AxonValue.num(2)), result.asArray());
        }
    }

    @Test
    void semCredencialAsAtribuicoesDeLiteraisContinuamSemCusto() {
        try (GatewayFixture fixture = GatewayFixture.memory(2); ShardGateway gateway = guarded(fixture)) {
            GatewaySession session = anonymous();

            gateway.execute(session, "LET $x = ['a', { b: 1 }]", null);

            assertEquals(2, session.vars().get("x").asArray().size());
        }
    }
}
