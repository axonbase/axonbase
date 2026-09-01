package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.common.Messages;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Endpoint GraphQL mínimo. Gera o schema a partir do {@code INFO FOR TABLE}
 * e traduz consultas para AxonQL.
 *
 * <p>Endpoint: {@code POST /graphql} com body {@code {"query": "...", "variables": {...}}}.
 */
public final class GraphQLServlet extends HttpServlet {

    private final Datastore ds;
    private final Function<HttpServletRequest, Session> sessionFactory;
    private final boolean requireAuth;

    public GraphQLServlet(Datastore ds, Function<HttpServletRequest, Session> sessionFactory, boolean requireAuth) {
        this.ds = ds;
        this.sessionFactory = sessionFactory;
        this.requireAuth = requireAuth;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session session = sessionFactory.apply(req);
        if (requireAuth && session.auth() == null) {
            json(resp, 401, "{\"errors\":[{\"message\":\"authentication required\"}]}");
            return;
        }
        String body = new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        AxonValue parsed;
        try {
            parsed = AxonJson.parseDocument(body);
        } catch (Exception e) {
            json(resp, 400, "{\"errors\":[{\"message\":\"" + esc(Messages.get("http_json_invalid")) + "\"}]}");
            return;
        }
        if (!parsed.isObject()) {
            json(resp, 400, "{\"errors\":[{\"message\":\"" + esc(Messages.get("mcp_body_invalid")) + "\"}]}");
            return;
        }
        Map<String, AxonValue> obj = parsed.asObject();
        String query = obj.get("query") != null ? obj.get("query").asString() : "";
        AxonValue vars = obj.get("variables");

        if (query.isBlank()) {
            json(resp, 400, "{\"errors\":[{\"message\":\"" + esc(Messages.get("graphql_query_empty")) + "\"}]}");
            return;
        }

        // Introspection query
        if (query.contains("__schema")) {
            String schema = buildSchema(session);
            json(resp, 200, "{\"data\":{\"__schema\":" + schema + "}}");
            return;
        }

        // Extrair nome da tabela da consulta
        String table = extractTable(query);
        if (table == null) {
            json(resp, 200, "{\"data\":{}}");
            return;
        }

        // Construir SELECT AxonQL e executar
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            json(resp, 400, "{\"errors\":[{\"message\":\"invalid table\"}]}");
            return;
        }

        String axonSql = "SELECT * FROM " + table;
        try {
            AxonValue result = ds.execute(axonSql, session, mapOf(vars));
            String jsonResult = AxonJson.write(result);
            // GraphQL espera {"data": {"<table>": [...]}}
            String graphqlResponse = "{\"data\":{\"" + table + "\":" + jsonResult + "}}";
            json(resp, 200, graphqlResponse);
        } catch (Exception e) {
            json(resp, 200, "{\"errors\":[{\"message\":\"" + esc(e.getMessage()) + "\"}]}");
        }
    }

    private String buildSchema(Session s) {
        try {
            AxonValue info = ds.execute("INFO FOR DATABASE", s, null);
            if (!info.isObject()) return "{\"types\":[]}";
            AxonValue tables = info.asObject().get("tables");
            if (tables == null || !tables.isArray()) return "{\"types\":[]}";

            StringBuilder sb = new StringBuilder("{\"types\":[");
            boolean first = true;
            for (AxonValue t : tables.asArray()) {
                String tableName = t.asString();
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"kind\":\"OBJECT\",\"name\":\"").append(tableName)
                    .append("\",\"fields\":[{\"name\":\"id\",\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}");
                // Adicionar campos do schema se disponíveis
                try {
                    AxonValue tableInfo = ds.execute("INFO FOR TABLE " + tableName, s, null);
                    if (tableInfo.isObject() && tableInfo.asObject().get("fields").isObject()) {
                        for (String field : tableInfo.asObject().get("fields").asObject().keySet()) {
                            sb.append(",{\"name\":\"").append(field)
                                .append("\",\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}");
                        }
                    }
                } catch (Exception ignored) {
                }
                sb.append("]}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"types\":[]}";
        }
    }

    /** Extrai o nome da tabela de uma consulta GraphQL simples. */
    private static String extractTable(String query) {
        // Formato: { person { name age } } ou query { person { ... } }
        int brace = query.indexOf('{');
        if (brace < 0) return null;
        String after = query.substring(brace + 1).trim();
        int space = after.indexOf(' ');
        int brace2 = after.indexOf('{');
        int end = Math.min(space > 0 ? space : Integer.MAX_VALUE, brace2 > 0 ? brace2 : Integer.MAX_VALUE);
        if (end == Integer.MAX_VALUE || end <= 0) return null;
        String table = after.substring(0, end).trim();
        return table.isEmpty() || table.startsWith("{") ? null : table;
    }

    private static Map<String, AxonValue> mapOf(AxonValue v) {
        return v != null && v.isObject() ? v.asObject() : Map.of();
    }

    private static void json(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(body);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
