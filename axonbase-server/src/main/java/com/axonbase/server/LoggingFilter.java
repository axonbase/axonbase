package com.axonbase.server;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

public final class LoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = resp.getStatus();
            String method = req.getMethod();
            String path = req.getRequestURI();
            String query = req.getQueryString();
            String fullPath = query == null ? path : path + "?" + query;
            System.out.println("{\"time\":\"" + Instant.now() + "\",\"method\":\""
                + esc(method) + "\",\"path\":\"" + esc(fullPath)
                + "\",\"status\":" + status + ",\"duration_ms\":" + elapsedMs
                + ",\"request_id\":\"" + esc(requestId) + "\"}");
            resp.setHeader("X-Request-Id", requestId);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}