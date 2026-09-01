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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Servidor MCP (Model Context Protocol) mínimo sobre SSE.
 *
 * <p>Endpoint SSE: {@code GET /mcp/sse}
 * <br>Endpoint mensagens: {@code POST /mcp}
 *
 * <p>Ferramentas expostas:
 * <ul>
 *   <li>{@code axon_select} — seleciona registros de uma tabela</li>
 *   <li>{@code axon_info} — informações do catálogo</li>
 * </ul>
 */
public final class McpServlet extends HttpServlet {

    private final Datastore ds;
    private final Function<HttpServletRequest, Session> sessionFactory;
    private final boolean requireAuth;
    private final AtomicLong msgId = new AtomicLong(0);
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    public McpServlet(Datastore ds, Function<HttpServletRequest, Session> sessionFactory, boolean requireAuth) {
        this.ds = ds;
        this.sessionFactory = sessionFactory;
        this.requireAuth = requireAuth;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if ("/sse".equals(path)) {
            handleSse(req, resp);
        } else {
            resp.setStatus(404);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null || "/message".equals(path)) {
            handleMessage(req, resp);
        } else {
            resp.setStatus(404);
        }
    }

    private void handleSse(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (requireAuth && sessionFactory.apply(req).auth() == null) {
            json(resp, 401, "{\"error\":\"authentication required\"}");
            return;
        }
        resp.setStatus(200);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        String sessionId = UUID.randomUUID().toString();
        McpSession session = new McpSession(sessionId, resp);
        sessions.put(sessionId, session);

        // Enviar evento de endpoint
        resp.getWriter().write("event: endpoint\ndata: /mcp/message?session_id=" + sessionId + "\n\n");
        resp.getWriter().flush();

        // Enviar lista de ferramentas
        String tools = "event: initialized\ndata: " + esc(toolsJson()) + "\n\n";
        resp.getWriter().write(tools);
        resp.getWriter().flush();

        // Manter conexão aberta
        try {
            while (session.isOpen()) {
                Thread.sleep(30000);
                resp.getWriter().write(": keepalive\n\n");
                resp.getWriter().flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessions.remove(sessionId);
        }
    }

    private void handleMessage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session s = sessionFactory.apply(req);
        if (requireAuth && s.auth() == null) {
            json(resp, 401, "{\"error\":\"authentication required\"}");
            return;
        }
        String body = new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        AxonValue parsed;
        try {
            parsed = AxonJson.parseDocument(body);
        } catch (Exception e) {
            json(resp, 400, "{\"error\":\"" + esc(Messages.get("http_json_invalid")) + "\"}");
            return;
        }
        if (!parsed.isObject()) {
            json(resp, 400, "{\"error\":\"" + esc(Messages.get("mcp_body_invalid")) + "\"}");
            return;
        }
        Map<String, AxonValue> obj = parsed.asObject();
        AxonValue id = obj.get("id");
        AxonValue method = obj.get("method");
        if (method == null || !method.isString()) {
            json(resp, 400, "{\"error\":\"" + esc(Messages.get("mcp_method_required")) + "\"}");
            return;
        }

        AxonValue params = obj.get("params");
        AxonValue result;
        try {
            result = switch (method.asString()) {
                case "tools/list" -> toolsResult();
                case "tools/call" -> callTool(params, s);
                case "resources/list" -> resourcesResult(s);
                default -> AxonValue.object(Map.of("error", AxonValue.str(Messages.get("rpc_method_unsupported", method.asString()))));
            };
        } catch (Exception e) {
            result = AxonValue.object(Map.of("error", AxonValue.str(e.getMessage())));
        }

        String response = "{\"id\":" + (id != null ? AxonJson.write(id) : "null")
            + ",\"result\":" + AxonJson.write(result) + "}";
        json(resp, 200, response);
    }

    private AxonValue callTool(AxonValue params, Session s) {
        if (params == null || !params.isObject()) {
            return AxonValue.object(Map.of("error", AxonValue.str(Messages.get("mcp_parameters_invalid"))));
        }
        Map<String, AxonValue> p = params.asObject();
        AxonValue name = p.get("name");
        if (name == null || !name.isString()) {
            return AxonValue.object(Map.of("error", AxonValue.str(Messages.get("mcp_name_required"))));
        }
        AxonValue arguments = p.get("arguments");
        Map<String, AxonValue> args = arguments != null && arguments.isObject()
            ? arguments.asObject() : Map.of();

        try {
            return switch (name.asString()) {
                case "axon_select" -> {
                    String table = args.get("table").asString();
                    if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException("invalid table");
                    }
                    int limit = args.containsKey("limit") ? Math.min(100, Math.max(1, (int) args.get("limit").asLong())) : 100;
                    yield ds.execute("SELECT * FROM " + table + " LIMIT " + limit, s, null);
                }
                case "axon_info" -> {
                    yield ds.execute("INFO FOR DATABASE", s, null);
                }
                default -> AxonValue.object(Map.of("error", AxonValue.str(Messages.get("mcp_tool_not_found", name.asString()))));
            };
        } catch (Exception e) {
            return AxonValue.object(Map.of("error", AxonValue.str(e.getMessage())));
        }
    }

    private AxonValue toolsResult() {
        return AxonValue.object(Map.of("tools", AxonValue.array(List.of(
            tool("axon_select", "Seleciona registros de uma tabela", Map.of("table", "string", "limit", "number?")),
            tool("axon_info", "Informações do catálogo do banco atual", Map.of())
        ))));
    }

    private AxonValue tool(String name, String description, Map<String, String> params) {
        Map<String, AxonValue> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var e : params.entrySet()) {
            String key = e.getKey();
            String type = e.getValue();
            boolean optional = type.endsWith("?");
            String cleanType = optional ? type.substring(0, type.length() - 1) : type;
            properties.put(key, AxonValue.object(Map.of(
                "type", AxonValue.str(cleanType),
                "description", AxonValue.str(key)
            )));
            if (!optional) required.add(key);
        }
        Map<String, AxonValue> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", AxonValue.str("object"));
        inputSchema.put("properties", AxonValue.object(properties));
        inputSchema.put("required", AxonValue.array(required.stream().map(AxonValue::str).toList()));

        return AxonValue.object(Map.of(
            "name", AxonValue.str(name),
            "description", AxonValue.str(description),
            "inputSchema", AxonValue.object(inputSchema)
        ));
    }

    private AxonValue resourcesResult(Session s) {
        try {
            AxonValue tables = ds.execute("INFO FOR DATABASE", s, null);
            List<AxonValue> resources = new ArrayList<>();
            if (tables.isObject() && tables.asObject().get("tables").isArray()) {
                for (AxonValue t : tables.asObject().get("tables").asArray()) {
                    resources.add(AxonValue.object(Map.of(
                        "uri", AxonValue.str("axon://" + s.namespace() + "/" + s.database() + "/" + t.asString()),
                        "name", AxonValue.str(t.asString()),
                        "mimeType", AxonValue.str("application/json")
                    )));
                }
            }
            return AxonValue.object(Map.of("resources", AxonValue.array(resources)));
        } catch (Exception e) {
            return AxonValue.object(Map.of("resources", AxonValue.array(List.of())));
        }
    }

    private String toolsJson() {
        AxonValue t = toolsResult();
        return AxonJson.write(t);
    }

    private static class McpSession {
        private final String id;
        private final HttpServletResponse response;
        private volatile boolean open = true;

        McpSession(String id, HttpServletResponse response) {
            this.id = id;
            this.response = response;
        }

        boolean isOpen() { return open; }
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
