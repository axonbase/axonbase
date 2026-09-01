package com.axonbase.server.auth;

import com.axonbase.common.Messages;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Emisor e verificador de JWT (JSON Web Token) con asinatura HS256. O headers
 * e payload codificados en base64url; a firmase usa HMAC-SHA256 sobre o secreto.
 * Para o MVP non hai soporte de expirración estrita nin de reserva.
 */
public final class Jwt {

    private static final String ALG = "HS256";

    private Jwt() {
    }

    public record Token(String value, String subject, long expiresAt) {
    }

    /**
     * Emite un JWT cos claims dados (ex. {@code id}, {@code ns}, {@code db}).
     *
     * @param secret  clave secreta do servidor
     * @param claims  map de claims (sen invadir sub/iat)
     * @param ttlMillis  tempo de vida en milisegundos
     */
    public static Token sign(String secret, Map<String, Object> claims, long ttlMillis) {
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long now = System.currentTimeMillis();
        var payload = new java.util.LinkedHashMap<String, Object>(claims);
        payload.put("iat", now / 1000);
        payload.put("exp", (now + ttlMillis) / 1000);
        String body = b64url(jackson(payload));
        String unsigned = header + "." + body;
        String sig = signPart(secret, unsigned);
        return new Token(unsigned + "." + sig, (String) claims.getOrDefault("sub", ""), now + ttlMillis);
    }

    private static String signPart(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("jwt_sign_failed"), e);
        }
    }

    private static String b64url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String jackson(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append('"').append(esc(s)).append('"');
            } else if (v instanceof Number n) {
                sb.append(n);
            } else if (v instanceof Boolean b) {
                sb.append(b);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Verifica que asinatura e devolve o payload parseado, ou {@code null} si inválido. */
    public static Map<String, Object> verify(String secret, String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String unsigned = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII),
            signPart(secret, unsigned).getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        AxonValue parsed = AxonJson.parseDocument(payloadJson);
        if (!parsed.isObject()) {
            return null;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (var e : parsed.asObject().entrySet()) {
            out.put(e.getKey(), primitive(e.getValue()));
        }
        return out;
    }

    private static Object primitive(AxonValue v) {
        return switch (v.type()) {
            case NUMBER -> v.isInteger() ? v.asLong() : v.asDouble();
            case STRING -> v.asString();
            case BOOL -> v.asBool();
            case NULL, NONE -> null;
            default -> v.toString();
        };
    }
}
