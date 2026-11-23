package com.axonbase.server;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

/** Rotas de {@code /admin/v1} que dependem de serviços opcionais (backup, eventos). */
final class AdminV1Extensions {

    private AdminV1Extensions() {
    }

    /** Devolve a resposta da rota, ou {@code null} quando nenhuma extensão a atende. */
    static AdminV1Response route(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                                 HttpServletRequest req, AdminV1Servlet.Caller caller) throws IOException {
        return null;
    }
}
