package com.axonbase.server;

import com.axonbase.common.Messages;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Limite de requisições por endereço de cliente. O endereço é o da conexão; {@code X-Forwarded-For} só é lido quando
 * a conexão vem de um proxy listado em {@code AXON_TRUSTED_PROXIES}, porque qualquer cliente escreve o que quiser nele.
 */
public final class RateLimitFilter implements Filter {

    /** Um balde parado há este tempo já está reposto: pode sair do mapa. */
    private static final long IDLE_NANOS = 60_000_000_000L;

    private final int permitsPerSecond;
    private final Set<String> trustedProxies;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    public RateLimitFilter(int permitsPerSecond) {
        this(permitsPerSecond, Set.of());
    }

    public RateLimitFilter(int permitsPerSecond, Set<String> trustedProxies) {
        this.permitsPerSecond = permitsPerSecond;
        this.trustedProxies = Set.copyOf(trustedProxies);
    }

    /** Lê a lista de endereços (sem faixas de rede) separados por vírgula. */
    static Set<String> trustedProxies(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(raw.split(","))
            .map(RateLimitFilter::normalize)
            .filter(address -> !address.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (permitsPerSecond <= 0) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String ip = clientAddress(req.getRemoteAddr(), forwardedFor(req), trustedProxies);
        long now = System.nanoTime();
        sweep(now);
        TokenBucket bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(permitsPerSecond));
        bucket.lastUsedNanos = now;
        if (!bucket.tryConsume()) {
            resp.setStatus(429);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("{\"error\":{\"code\":-32029,\"message\":\"" + Messages.get("rate_limit_exceeded") + "\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Endereço que conta para o limite. Vindo de proxy confiável, percorre a cadeia da direita para a esquerda e usa o
     * primeiro endereço que não é proxy confiável: os valores à esquerda são de quem chamou e não valem como prova.
     */
    static String clientAddress(String remoteAddress, List<String> forwarded, Set<String> trustedProxies) {
        String remote = remoteAddress == null ? "unknown" : normalize(remoteAddress);
        if (!trustedProxies.contains(remote)) {
            return remote;
        }
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            String hop = normalize(forwarded.get(i));
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return remote;
    }

    private static List<String> forwardedFor(HttpServletRequest req) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(req.getHeaders("X-Forwarded-For"))) {
            Collections.addAll(hops, header.split(","));
        }
        return hops;
    }

    /** Forma única do endereço, para IPv6 comparar igual com ou sem colchetes e zeros. */
    private static String normalize(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        // só literais IPv6 passam pelo InetAddress: nada aqui consulta DNS
        if (a.indexOf(':') >= 0 && a.matches("[0-9a-f:.]+")) {
            try {
                return InetAddress.getByName(a).getHostAddress();
            } catch (UnknownHostException invalid) {
                return a;
            }
        }
        return a;
    }

    private void sweep(long now) {
        long last = lastSweepNanos.get();
        if (now - last >= IDLE_NANOS && lastSweepNanos.compareAndSet(last, now)) {
            buckets.values().removeIf(bucket -> now - bucket.lastUsedNanos >= IDLE_NANOS);
        }
    }

    static final class TokenBucket {
        private final long capacity;
        private final double refillPerNano;
        private final AtomicLong tokens;
        private volatile long lastRefillNanos;
        private volatile long lastUsedNanos;

        TokenBucket(int permitsPerSecond) {
            this.capacity = permitsPerSecond * 2;
            this.refillPerNano = permitsPerSecond / 1_000_000_000.0;
            this.tokens = new AtomicLong(permitsPerSecond);
            this.lastRefillNanos = System.nanoTime();
            this.lastUsedNanos = this.lastRefillNanos;
        }

        boolean tryConsume() {
            refill();
            while (true) {
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            long added = (long) (elapsed * refillPerNano);
            // o marco só avança quando entra ficha: senão uma rajada contínua impediria a reposição para sempre
            if (added >= 1) {
                lastRefillNanos = now;
                tokens.updateAndGet(t -> Math.min(capacity, t + added));
            }
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
