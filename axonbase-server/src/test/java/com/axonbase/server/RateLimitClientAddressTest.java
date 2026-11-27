package com.axonbase.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Qual endereço conta para o limite de requisições, com e sem proxies confiáveis. */
class RateLimitClientAddressTest {

    private static final Set<String> PROXIES = RateLimitFilter.trustedProxies("10.0.0.1, 10.0.0.2");

    @Test
    void conexaoForaDaListaIgnoraOCabecalho() {
        assertEquals("198.51.100.7",
            RateLimitFilter.clientAddress("198.51.100.7", List.of("203.0.113.9"), PROXIES));
    }

    @Test
    void proxyConfiavelUsaOPrimeiroEnderecoNaoConfiavelDaDireita() {
        assertEquals("198.51.100.7", RateLimitFilter.clientAddress("10.0.0.1",
            List.of("203.0.113.9", " 198.51.100.7", " 10.0.0.2"), PROXIES));
    }

    @Test
    void proxyConfiavelSemCabecalhoContaOProprioProxy() {
        assertEquals("10.0.0.1", RateLimitFilter.clientAddress("10.0.0.1", List.of(), PROXIES));
    }

    @Test
    void ipv6ComparaIgualComOuSemColchetes() {
        Set<String> local = RateLimitFilter.trustedProxies("::1");

        assertEquals("198.51.100.7", RateLimitFilter.clientAddress("[0:0:0:0:0:0:0:1]",
            List.of("198.51.100.7"), local));
    }
}
