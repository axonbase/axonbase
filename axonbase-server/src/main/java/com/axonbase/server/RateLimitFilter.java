package com.axonbase.server;

import com.axonbase.common.Messages;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimitFilter implements Filter {

    private final int permitsPerSecond;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
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
        String ip = remoteIp(req);
        TokenBucket bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(permitsPerSecond));
        if (!bucket.tryConsume()) {
            resp.setStatus(429);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("{\"error\":{\"code\":-32029,\"message\":\"" + Messages.get("rate_limit_exceeded") + "\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    static String remoteIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String remote = req.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    static final class TokenBucket {
        private final long capacity;
        private final double refillPerNano;
        private final AtomicLong tokens;
        private volatile long lastRefillNanos;

        TokenBucket(int permitsPerSecond) {
            this.capacity = permitsPerSecond * 2;
            this.refillPerNano = permitsPerSecond / 1_000_000_000.0;
            this.tokens = new AtomicLong(permitsPerSecond);
            this.lastRefillNanos = System.nanoTime();
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
            lastRefillNanos = now;
            double added = elapsed * refillPerNano;
            if (added >= 1) {
                tokens.updateAndGet(t -> Math.min(capacity, t + (long) added));
            }
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
