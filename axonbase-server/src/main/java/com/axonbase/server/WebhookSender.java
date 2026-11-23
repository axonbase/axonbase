package com.axonbase.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Entrega eventos por HTTP POST. Com segredo configurado, o corpo vai assinado em
 * {@code X-Axon-Signature: sha256=<hex>} (HMAC-SHA256 do corpo exato). Também envia
 * {@code X-Axon-Timestamp} (segundos Unix) e {@code X-Axon-Signature-V2: sha256=<hex>}, o HMAC-SHA256
 * de {@code timestamp + "." + corpo}.
 */
final class WebhookSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI url;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    WebhookSender(String url, String secret) {
        this.url = URI.create(url);
        this.secret = secret == null ? "" : secret;
        if (this.secret.isEmpty()) {
            AxonLog.log("warn", "webhook_unsigned",
                "webhook sem AXON_WEBHOOK_SECRET: as entregas saem sem assinatura");
        }
    }

    /** Envia uma linha de evento; comentários de keepalive são ignorados. */
    void send(String line) {
        if (line.startsWith(":")) {
            return;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
        if (!secret.isEmpty()) {
            // formato antigo (só o corpo), mantido por compatibilidade
            b.header("X-Axon-Signature", "sha256=" + sign(line));
            // formato novo: assina "timestamp.corpo" para o receptor rejeitar repetições
            String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
            b.header("X-Axon-Timestamp", timestamp);
            b.header("X-Axon-Signature-V2", "sha256=" + sign(timestamp + "." + line));
        }
        http.sendAsync(b.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err != null || resp.statusCode() >= 300) {
                    AxonLog.log("warn", "webhook_failed", "entrega de webhook falhou",
                        Map.of("error", err != null ? String.valueOf(err.getMessage())
                            : "HTTP " + resp.statusCode()));
                }
            });
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
