package com.axonbase.server;

import com.axonbase.server.auth.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Log de requisição no formato JSON unificado ({@link AxonLog}), com usuário e ns/db. */
public final class LoggingFilter implements Filter {

    private final AuthService auth;

    public LoggingFilter() {
        this(null);
    }

    public LoggingFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = FastIds.uuid();
        }
        resp.setHeader("X-Request-Id", requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = resp.getStatus();
            String query = req.getQueryString();
            String path = req.getRequestURI();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("method", req.getMethod());
            f.put("path", query == null ? path : path + "?" + query);
            f.put("status", status);
            f.put("duration_ms", elapsedMs);
            f.put("request_id", requestId);
            f.put("user", user(req));
            f.put("ns", req.getHeader("Axon-Ns"));
            f.put("db", req.getHeader("Axon-Db"));
            AxonLog.log(status >= 500 ? "error" : "info", "http_request", req.getMethod() + " " + path, f);
        }
    }

    private String user(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (auth == null || header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        Map<String, Object> claims = auth.claims(header.substring("Bearer ".length()).trim());
        return claims == null ? null : String.valueOf(claims.get("id"));
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
