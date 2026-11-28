package com.axonbase.server;

import com.axonbase.value.AxonJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Cliente HTTP mínimo dos testes de segurança: login real pelo servidor e chamadas com cabeçalhos. */
final class SecurityHttp {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;

    SecurityHttp(int port) {
        this.port = port;
    }

    /** POST com cabeçalhos em pares nome, valor. */
    HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Token do root pela rota /signin. */
    String rootToken(String pass) throws Exception {
        HttpResponse<String> response = post("/signin", "{\"user\":\"root\",\"pass\":\"" + pass + "\"}");
        return AxonJson.parseDocument(response.body()).asObject().get("token").asString();
    }

    /** Login de usuário de namespace ou banco pelo RPC sobre HTTP, como um cliente faz. */
    String signin(String ns, String db, String user, String pass) throws Exception {
        HttpResponse<String> response = post("/rpc",
            "{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"" + user + "\",\"pass\":\"" + pass + "\"}]}",
            "Axon-Ns", ns, "Axon-Db", db);
        return AxonJson.parseDocument(response.body()).asObject().get("result").asString();
    }

    static String[] bearer(String token, String ns, String db) {
        return new String[] {"Authorization", "Bearer " + token, "Axon-Ns", ns, "Axon-Db", db};
    }
}
