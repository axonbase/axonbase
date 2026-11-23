package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Truststore enviado pela API sobrevive a restart (dois Datastore sobre o mesmo backend). */
class TruststorePersistTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String signin(AxonServer s) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + s.port() + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private static HttpResponse<String> call(AxonServer s, String method, String path, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + s.port() + "/admin/v1" + path))
            .header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void upload(AxonServer s, String token, String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ByteArrayOutputStream jks = new ByteArrayOutputStream();
        ks.store(jks, "senha123".toCharArray());
        String b = "----persist";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String[] p : new String[][] {{"name", name}, {"password", "senha123"}}) {
            body.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + p[0] + "\"\r\n\r\n" + p[1]
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.jks\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(jks.toByteArray());
        body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + s.port() + "/admin/v1/truststores"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + b)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, r.statusCode(), r.body());
    }

    @Test
    void truststoreEnviadoSobreviveARestartERemocaoTambem() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        String name = "persist" + System.nanoTime();
        AxonServer a = AxonServer.startRandomPort(new Datastore(backend), "secret-testing", "root", "pw", true);
        try {
            upload(a, signin(a), name);
        } finally {
            a.stop();
        }
        AxonServer b = AxonServer.startRandomPort(new Datastore(backend), "secret-testing", "root", "pw", true);
        try {
            String t = signin(b);
            HttpResponse<String> list = call(b, "GET", "/truststores", t);
            assertTrue(list.body().contains("\"" + name + "\""), list.body());
            assertEquals(204, call(b, "DELETE", "/truststores/" + name, t).statusCode());
        } finally {
            b.stop();
        }
        AxonServer c = AxonServer.startRandomPort(new Datastore(backend), "secret-testing", "root", "pw", true);
        try {
            assertTrue(!call(c, "GET", "/truststores", signin(c)).body().contains(name));
        } finally {
            c.stop();
        }
    }
}
