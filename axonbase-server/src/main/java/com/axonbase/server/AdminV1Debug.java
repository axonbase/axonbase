package com.axonbase.server;

import com.axonbase.core.pl.DebugException;
import com.axonbase.core.pl.DebugManager;
import com.axonbase.core.pl.DebugRequest;
import com.axonbase.core.pl.DebugSession;
import com.axonbase.core.security.Authz;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rotas de depuração do AxonPL sob {@code /admin/v1}. O contrato está em {@code docs/axonpl-depuracao.md}.
 */
final class AdminV1Debug {

    private AdminV1Debug() {
    }

    /** Devolve a resposta da rota, ou {@code null} quando a rota não é de depuração. */
    static AdminV1Response route(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                                 HttpServletRequest req, AdminV1Servlet.Caller c) throws IOException {
        int n = seg.size();
        boolean start = method.equals("POST") && n == 6 && seg.get(0).equals("databases")
            && seg.get(3).equals("programs") && seg.get(5).equals("debug");
        boolean sessions = n >= 2 && seg.get(0).equals("debug") && seg.get(1).equals("sessions");
        if (!start && !sessions) {
            return null;
        }
        DebugManager mgr = srv.ds().programs().debug();
        try {
            if (start) {
                return start(srv, api, mgr, seg.get(1), seg.get(2), seg.get(4), req, c);
            }
            if (n == 2 && method.equals("GET")) {
                return AdminV1Servlet.ok(list(srv, api, mgr, c));
            }
            if (n < 3) {
                return null;
            }
            DebugSession s = mgr.get(seg.get(2));
            if (s == null) {
                throw AdminV1Servlet.notFound();
            }
            guard(api, c, s.ns(), s.db());
            if (n == 3 && method.equals("GET")) {
                return AdminV1Servlet.ok(s.describe());
            }
            if (n == 3 && method.equals("DELETE")) {
                mgr.remove(s.id());
                return AdminV1Servlet.noContent();
            }
            if (n == 4 && method.equals("GET") && seg.get(3).equals("events")) {
                return AdminV1Servlet.ok(s.events(longParam(req, "after", 0), longParam(req, "wait", 0)));
            }
            if (n == 4 && method.equals("POST") && seg.get(3).equals("command")) {
                Map<String, AxonValue> b = api.object(api.body(req));
                return AdminV1Servlet.ok(s.command(api.text(b, "command", true)));
            }
            if (n == 4 && method.equals("PUT") && seg.get(3).equals("breakpoints")) {
                Map<String, AxonValue> b = api.object(api.body(req));
                s.breakpoints(lines(b.get("lines")));
                return AdminV1Servlet.ok(s.describe());
            }
            if (n == 4 && method.equals("POST") && seg.get(3).equals("evaluate")) {
                Map<String, AxonValue> b = api.object(api.body(req));
                return AdminV1Servlet.ok(s.evaluate(api.text(b, "expression", true)));
            }
            return null;
        } catch (DebugException e) {
            throw map(e);
        }
    }

    private static AdminV1Response start(AxonServer srv, AdminV1Servlet api, DebugManager mgr, String ns, String db,
                                         String program, HttpServletRequest req, AdminV1Servlet.Caller c)
            throws IOException {
        guard(api, c, ns, db);
        Map<String, AxonValue> b = api.object(api.body(req));
        boolean commit = flag(b, "commit", false);
        if (commit) {
            api.needRole(c, Authz.Role.ADMIN);
            api.needLeader();
        }
        List<AxonValue> args = new ArrayList<>();
        AxonValue a = b.get("args");
        if (a != null && !a.isNull() && !a.isNone()) {
            if (!a.isArray()) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "args deve ser uma lista");
            }
            args.addAll(a.asArray());
        }
        boolean profile = flag(b, "profile", false);
        Set<Integer> bps = lines(b.get("breakpoints"));
        boolean stopOnEntry = flag(b, "stopOnEntry", !profile && bps.isEmpty());
        DebugSession s = mgr.start(new DebugRequest(ns, db, c.auth(), program, args, bps, stopOnEntry, commit,
            profile));
        return AdminV1Servlet.created(s.describe());
    }

    private static AxonValue list(AxonServer srv, AdminV1Servlet api, DebugManager mgr, AdminV1Servlet.Caller c) {
        List<AxonValue> out = new ArrayList<>();
        for (DebugSession s : mgr.list()) {
            if (c.claims() != null && !srv.auth().allows(c.claims(), s.ns(), s.db())) {
                continue;
            }
            Map<String, AxonValue> m = new LinkedHashMap<>(s.describe().asObject());
            m.keySet().removeAll(Set.of("source", "pause", "result", "log", "profile"));
            out.add(AxonValue.object(m));
        }
        return AxonValue.array(out);
    }

    /** Escopo do banco e papel diferente de viewer (o mesmo nível que CREATE FUNCTION). */
    private static void guard(AdminV1Servlet api, AdminV1Servlet.Caller c, String ns, String db) {
        api.needScope(c, ns, db, Authz.Role.NONE);
        if (c.role() == Authz.Role.VIEWER) {
            throw new AdminV1Servlet.ApiError(403, "FORBIDDEN", "depurar exige papel operator ou superior");
        }
    }

    private static boolean flag(Map<String, AxonValue> b, String key, boolean fallback) {
        AxonValue v = b.get(key);
        if (v == null || v.isNull() || v.isNone()) {
            return fallback;
        }
        if (!v.isBool()) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", key + " deve ser verdadeiro ou falso");
        }
        return v.asBool();
    }

    private static Set<Integer> lines(AxonValue v) {
        Set<Integer> out = new LinkedHashSet<>();
        if (v == null || v.isNull() || v.isNone()) {
            return out;
        }
        if (!v.isArray()) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "a lista de linhas deve ser um array de inteiros");
        }
        for (AxonValue e : v.asArray()) {
            if (!e.isInteger()) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "linha invalida: " + e);
            }
            out.add((int) e.asLong());
        }
        return out;
    }

    private static long longParam(HttpServletRequest req, String name, long fallback) {
        String v = req.getParameter(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "parametro " + name + " invalido");
        }
    }

    private static AdminV1Servlet.ApiError map(DebugException e) {
        return switch (e.kind()) {
            case BAD_REQUEST -> new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
            case NOT_FOUND -> new AdminV1Servlet.ApiError(404, "NOT_FOUND", e.getMessage());
            case CONFLICT -> new AdminV1Servlet.ApiError(409, "CONFLICT", e.getMessage());
            case FORBIDDEN -> new AdminV1Servlet.ApiError(403, "FORBIDDEN", e.getMessage());
            case LIMIT -> new AdminV1Servlet.ApiError(429, "TOO_MANY_DEBUG_SESSIONS", e.getMessage());
        };
    }
}
