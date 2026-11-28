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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Servidor MCP (Model Context Protocol) mínimo.
 *
 * <p>Endpoint de mensagens: {@code POST /mcp}, com a resposta JSON-RPC no corpo.
 * <br>Transporte antigo: {@code GET /mcp/sse} abre o canal, e a resposta de cada {@code POST} no endereço anunciado
 * chega por ele. Mensagens sem o campo {@code jsonrpc} mantêm o formato simples anterior.
 *
 * <p>Ferramentas expostas:
 * <ul>
 *   <li>{@code axon_select} — seleciona registros de uma tabela</li>
 *   <li>{@code axon_info} — informações do catálogo</li>
 * </ul>
 */
public final class McpServlet extends HttpServlet {

    /** Versões do protocolo aceitas; a primeira é oferecida a quem pede uma desconhecida. */
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final Datastore ds;
    private final Function<HttpServletRequest, Session> sessionFactory;
    private final boolean requireAuth;
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
        } else if (path == null || "/".equals(path) || "/message".equals(path)) {
            // não há canal de eventos neste endereço
            resp.setStatus(405);
            resp.setHeader("Allow", "POST");
        } else {
            resp.setStatus(404);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null || "/".equals(path) || "/message".equals(path)) {
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
        McpSession session = new McpSession(resp, req.getHeader("Authorization"));
        sessions.put(sessionId, session);

        try {
            // Enviar evento de endpoint e a lista de ferramentas
            session.write("event: endpoint\ndata: /mcp/message?session_id=" + sessionId + "\n\n");
            session.write("event: initialized\ndata: " + esc(toolsJson()) + "\n\n");

            // Manter conexão aberta
            while (!session.awaitClose(30)) {
                if (!session.write(": keepalive\n\n")) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessions.remove(sessionId);
            session.close();
        }
    }

    @Override
    public void destroy() {
        sessions.values().forEach(McpSession::close);
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
        if (isJsonRpcBatch(parsed)) {
            List<String> replies = parsed.asArray().stream()
                .map(message -> reply(message.asObject(), s))
                .filter(Objects::nonNull)
                .toList();
            deliver(req, resp, replies.isEmpty() ? null : "[" + String.join(",", replies) + "]");
            return;
        }
        if (parsed.isObject() && isJsonRpc(parsed.asObject())) {
            deliver(req, resp, reply(parsed.asObject(), s));
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

    private static boolean isJsonRpc(Map<String, AxonValue> message) {
        AxonValue version = message.get("jsonrpc");
        return version != null && version.isString() && version.asString().equals("2.0");
    }

    private static boolean isJsonRpcBatch(AxonValue parsed) {
        return parsed.isArray() && !parsed.asArray().isEmpty()
            && parsed.asArray().stream().allMatch(m -> m.isObject() && isJsonRpc(m.asObject()));
    }

    /** A resposta JSON-RPC de uma mensagem, ou {@code null} para notificação e resposta do cliente. */
    private String reply(Map<String, AxonValue> message, Session s) {
        AxonValue id = message.get("id");
        AxonValue method = message.get("method");
        if (method == null || !method.isString()) {
            boolean clientReply = message.containsKey("result") || message.containsKey("error");
            return clientReply ? null : error(id, INVALID_REQUEST, Messages.get("mcp_method_required"));
        }
        if (id == null) {
            return null;
        }
        AxonValue params = message.get("params");
        try {
            AxonValue result = switch (method.asString()) {
                case "initialize" -> initializeResult(params);
                case "ping" -> AxonValue.object(Map.of());
                case "tools/list" -> toolsResult();
                case "tools/call" -> toolContent(params, s);
                case "resources/list" -> resourcesResult(s);
                default -> throw new McpError(METHOD_NOT_FOUND,
                    Messages.get("rpc_method_unsupported", method.asString()));
            };
            Map<String, AxonValue> response = new LinkedHashMap<>();
            response.put("jsonrpc", AxonValue.str("2.0"));
            response.put("id", id);
            response.put("result", result);
            return AxonJson.write(AxonValue.object(response));
        } catch (McpError e) {
            return error(id, e.code, e.getMessage());
        } catch (Exception e) {
            return error(id, INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private static String error(AxonValue id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + (id != null ? AxonJson.write(id) : "null")
            + ",\"error\":{\"code\":" + code + ",\"message\":" + AxonJson.write(AxonValue.str(message)) + "}}";
    }

    private void deliver(HttpServletRequest req, HttpServletResponse resp, String reply) throws IOException {
        if (reply == null) {
            resp.setStatus(202);
            return;
        }
        String sessionId = sessionId(req.getQueryString());
        if (sessionId == null) {
            json(resp, 200, reply);
            return;
        }
        McpSession channel = sessions.get(sessionId);
        // o canal só recebe respostas pedidas com a mesma credencial que o abriu
        if (channel == null || !Objects.equals(channel.authorization, req.getHeader("Authorization"))
            || !channel.write("event: message\ndata: " + reply + "\n\n")) {
            json(resp, 404, "{\"error\":\"" + esc(Messages.get("mcp_session_not_found")) + "\"}");
            return;
        }
        resp.setStatus(202);
    }

    /** Lido da query string: {@code getParameter} consumiria o corpo enviado como formulário. */
    private static String sessionId(String query) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith("session_id=")) {
                return pair.substring("session_id=".length());
            }
        }
        return null;
    }

    private AxonValue initializeResult(AxonValue params) {
        AxonValue asked = params != null && params.isObject() ? params.asObject().get("protocolVersion") : null;
        String version = asked != null && asked.isString() && PROTOCOL_VERSIONS.contains(asked.asString())
            ? asked.asString() : PROTOCOL_VERSIONS.get(0);
        return AxonValue.object(Map.of(
            "protocolVersion", AxonValue.str(version),
            "capabilities", AxonValue.object(Map.of("tools", AxonValue.object(Map.of()))),
            "serverInfo", AxonValue.object(Map.of(
                "name", AxonValue.str("axonbase"),
                "version", AxonValue.str(BuildInfo.version())))));
    }

    /** Resultado de ferramenta no formato do protocolo: a falha da execução vai no conteúdo, com isError. */
    private AxonValue toolContent(AxonValue params, Session s) throws Exception {
        AxonValue value;
        boolean failed = false;
        try {
            value = runTool(params, s);
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            value = failure(e);
            failed = true;
        }
        return AxonValue.object(Map.of(
            "content", AxonValue.array(List.of(AxonValue.object(Map.of(
                "type", AxonValue.str("text"),
                "text", AxonValue.str(AxonJson.write(value)))))),
            "isError", AxonValue.bool(failed)));
    }

    private AxonValue callTool(AxonValue params, Session s) {
        try {
            return runTool(params, s);
        } catch (Exception e) {
            return failure(e);
        }
    }

    private static AxonValue failure(Exception e) {
        AxonValue message = AxonValue.str(String.valueOf(e.getMessage()));
        if (e instanceof com.axonbase.common.AxonError axon) {
            return AxonValue.object(Map.of("error", message, "code", AxonValue.num(axon.code())));
        }
        return AxonValue.object(Map.of("error", message));
    }

    private AxonValue runTool(AxonValue params, Session s) throws Exception {
        if (params == null || !params.isObject()) {
            throw new McpError(INVALID_PARAMS, Messages.get("mcp_parameters_invalid"));
        }
        Map<String, AxonValue> p = params.asObject();
        AxonValue name = p.get("name");
        if (name == null || !name.isString()) {
            throw new McpError(INVALID_PARAMS, Messages.get("mcp_name_required"));
        }
        AxonValue arguments = p.get("arguments");
        Map<String, AxonValue> args = arguments != null && arguments.isObject()
            ? arguments.asObject() : Map.of();

        return switch (name.asString()) {
            case "axon_select" -> {
                String table = args.get("table").asString();
                if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("invalid table");
                }
                int limit = args.containsKey("limit") ? Math.min(100, Math.max(1, (int) args.get("limit").asLong())) : 100;
                yield ds.execute("SELECT * FROM " + table + " LIMIT " + limit, s, null);
            }
            case "axon_call" -> {
                AxonValue n = args.get("name");
                AxonValue a = args.get("args");
                yield ProgramCalls.call(ds, s, n == null || !n.isString() ? null : n.asString(),
                    a != null && a.isArray() ? a.asArray() : List.of());
            }
            case "axon_info" -> ds.execute("INFO FOR DATABASE", s, null);
            default -> throw new McpError(INVALID_PARAMS, Messages.get("mcp_tool_not_found", name.asString()));
        };
    }

    private AxonValue toolsResult() {
        return AxonValue.object(Map.of("tools", AxonValue.array(List.of(
            tool("axon_select", "Seleciona registros de uma tabela", Map.of("table", "string", "limit", "number?")),
            tool("axon_call", "Chama uma funcao, procedure ou funcao de package AxonPL (pkg::fn) pelo nome",
                Map.of("name", "string", "args", "array?")),
            tool("axon_info","Informações do catálogo do banco atual", Map.of())
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

    private static final class McpSession {
        private final HttpServletResponse response;
        private final String authorization;
        private final CountDownLatch closed = new CountDownLatch(1);

        McpSession(HttpServletResponse response, String authorization) {
            this.response = response;
            this.authorization = authorization;
        }

        /** Escreve no canal, de qualquer thread; devolve {@code false} se ele já se foi. */
        synchronized boolean write(String frame) throws IOException {
            if (closed.getCount() == 0) {
                return false;
            }
            java.io.PrintWriter writer = response.getWriter();
            writer.write(frame);
            writer.flush();
            return !writer.checkError();
        }

        boolean awaitClose(int seconds) throws InterruptedException {
            return closed.await(seconds, TimeUnit.SECONDS);
        }

        synchronized void close() {
            closed.countDown();
        }
    }

    private static final class McpError extends Exception {
        private final int code;

        McpError(int code, String message) {
            super(message);
            this.code = code;
        }
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
