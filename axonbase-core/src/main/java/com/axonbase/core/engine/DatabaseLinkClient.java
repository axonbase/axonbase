package com.axonbase.core.engine;

import com.axonbase.common.Messages;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

/**
 * Client for querying remote AxonBase servers via DATABASE LINK.
 * Uses HTTP JSON-RPC over the /sql endpoint (REST-style POST with Axon-Ns/Axon-Db headers).
 * Connection pool reuses HttpClient per remote endpoint.
 */
public class DatabaseLinkClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, HttpClient> clients = new ConcurrentHashMap<>();

    private HttpClient httpClient(Datastore.DatabaseLinkDef def) {
        String key = def.url() + "|" + def.ns() + "|" + def.db();
        return clients.computeIfAbsent(key, k -> HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build());
    }

    /**
     * Executes an AxonQL query on a remote server via its REST endpoint.
     * Returns a list of AxonValue objects (each row is an object).
     */
    public List<AxonValue> query(Datastore.DatabaseLinkDef def, String axonql) {
        HttpClient client = httpClient(def);
        try {
            String baseUrl = httpBaseUrl(def.url());
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sql"))
                .header("Content-Type", "text/plain")
                .header("Axon-Ns", def.ns())
                .header("Axon-Db", def.db())
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(axonql, StandardCharsets.UTF_8));
            String token = acquireToken(client, baseUrl, def);
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(Messages.get("database_link_query_failed", response.statusCode(), response.body()));
            }
            String body = response.body().strip();
            if (body.isEmpty() || "null".equals(body)) {
                return List.of();
            }
            // Parse JSON response
            AxonValue parsed = AxonJson.decode(body.getBytes(StandardCharsets.UTF_8));
            if (parsed.isObject() && parsed.asObject().containsKey("status")
                && "ERR".equals(parsed.asObject().get("status").asString())) {
                String detail = parsed.asObject().containsKey("detail")
                    ? parsed.asObject().get("detail").asString() : Messages.get("database_link_unknown_error");
                throw new RuntimeException(Messages.get("database_link_remote_error", detail));
            }
            if (parsed.isArray()) {
                return parsed.asArray();
            }
            if (parsed.isObject()) {
                return List.of(parsed);
            }
            // Scalar result (SELECT VALUE returns string/number/bool directly)
            if (parsed.isString() || parsed.isBool() || parsed.isNumber()) {
                return List.of(parsed);
            }
            return List.of();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("database_link_connection_failed", e.getMessage()), e);
        }
    }

    private static String httpBaseUrl(String endpoint) {
        String url = endpoint.replaceAll("/rpc/ws$", "").replaceAll("/$", "");
        if (url.startsWith("ws://")) {
            return "http://" + url.substring(5);
        }
        if (url.startsWith("wss://")) {
            return "https://" + url.substring(6);
        }
        return url;
    }

    private static String acquireToken(HttpClient client, String baseUrl, Datastore.DatabaseLinkDef def)
        throws Exception {
        if (def.user() == null || def.user().isBlank() || def.password() == null || def.password().isBlank()) {
            return null;
        }
        String credentials = AxonJson.write(AxonValue.object(Map.of(
            "user", AxonValue.str(def.user()), "pass", AxonValue.str(def.password()))));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/signin"))
            .header("Content-Type", "application/json")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(credentials, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException(Messages.get("database_link_query_failed", response.statusCode(), response.body()));
        }
        AxonValue payload = AxonJson.decode(response.body().getBytes(StandardCharsets.UTF_8));
        AxonValue token = payload.isObject() ? payload.asObject().get("token") : null;
        if (token == null || !token.isString() || token.asString().isBlank()) {
            throw new RuntimeException(Messages.get("database_link_unknown_error"));
        }
        return token.asString();
    }

    /** Fecha todos os clientes HTTP. */
    public void close() {
        clients.clear();
    }
}
