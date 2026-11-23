package com.axonbase.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Aplica {@link AdmissionControl}: 503 imediato quando o limite de requisições de dados é atingido. */
public final class AdmissionFilter implements Filter {

    private final AdmissionControl control;

    public AdmissionFilter(AdmissionControl control) {
        this.control = control;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        String path = ((HttpServletRequest) request).getRequestURI();
        if (AdmissionControl.isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!control.tryEnter()) {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(503);
            resp.setHeader("Retry-After", "1");
            resp.setContentType("application/json");
            resp.getOutputStream().write(
                "{\"status\":\"ERR\",\"kind\":\"OVERLOADED\",\"detail\":\"server busy\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            control.exit();
        }
    }

    @Override
    public void init(jakarta.servlet.FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }
}
