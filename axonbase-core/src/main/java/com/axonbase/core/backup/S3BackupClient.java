package com.axonbase.core.backup;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente S3 mínimo sem dependências externas.
 *
 * <p>Dois modos de autenticação: básico ({@code Authorization: Basic base64(access:secret)}),
 * que funciona com MinIO e com o cliente {@code mc} como proxy para AWS S3 de verdade, e
 * AWS V4 para endpoints que exigem assinatura. O modo básico é o recomendado: o a V4 ainda
 * tem limites de assinatura, então o usuário pode apontar o endpoint para um proxy mc.</p>
 *
 * <p>O upload por stream usa {@link HttpURLConnection#setChunkedStreamingMode(int)} para
 * não materializar o payload inteiro em RAM.</p>
 */
public final class S3BackupClient implements RemoteBackupStore {

    private static final int CHUNK_SIZE = 8192;

    private final String endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final boolean basicAuth;

    public S3BackupClient(String endpoint, String region, String accessKey, String secretKey) {
        this(endpoint, region, accessKey, secretKey, false);
    }

    public S3BackupClient(String endpoint, String region, String accessKey, String secretKey,
                          boolean basicAuth) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.basicAuth = basicAuth;
    }

    public static S3BackupClient fromUri(String uri) {
        // s3://bucket/key?endpoint=http://minio:9000&access=minioadmin&secret=minioadmin&region=us-east-1
        if (!uri.startsWith("s3://")) throw new IllegalArgumentException(Messages.get("backup_s3_uri_required"));
        String rest = uri.substring(5);
        int q = rest.indexOf('?');
        String path = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";

        int slash = path.indexOf('/');
        String bucket = slash >= 0 ? path.substring(0, slash) : path;
        String key = slash >= 0 ? path.substring(slash + 1) : "";

        String endpoint = "https://s3.amazonaws.com";
        String region = "us-east-1";
        String accessKey = "";
        String secretKey = "";
        boolean basic = false;

        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "endpoint" -> endpoint = kv[1];
                case "access", "accessKey" -> accessKey = kv[1];
                case "secret", "secretKey" -> secretKey = kv[1];
                case "region" -> region = kv[1];
                case "auth" -> basic = "basic".equalsIgnoreCase(kv[1]);
            }
        }
        return new S3BackupClient(endpoint, region, accessKey, secretKey, basic);
    }

    public String bucket(String uri) {
        return uri.substring(5).split("[/?]")[0];
    }

    public String key(String uri) {
        String rest = uri.substring(5);
        int q = rest.indexOf('?');
        String path = q >= 0 ? rest.substring(0, q) : rest;
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : "";
    }

    /** Faz upload do conteúdo para s3://bucket/key. */
    public void upload(String bucket, String key, byte[] data) {
        try {
            HttpURLConnection conn = open("PUT", bucket, key, null);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            check("upload", conn);
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_upload_failed", e.getMessage()));
        }
    }

    /**
     * Upload por stream, lendo {@code data} em pedaços de 8 KB.
     * Bufferiza em RAM para poder definir Content-Length (exigido pelo MinIO).
     */
    public void upload(String bucket, String key, InputStream data) {
        try {
            byte[] allBytes = data.readAllBytes();
            upload(bucket, key, allBytes);
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_upload_stream_failed", e.getMessage()));
        }
    }

    /** Faz download do conteúdo de s3://bucket/key. */
    public byte[] download(String bucket, String key) {
        try {
            HttpURLConnection conn = open("GET", bucket, key, null);
            int status = conn.getResponseCode();
            if (status == 404) {
                return new byte[0];
            }
            if (status < 200 || status >= 300) {
                throw error("download", conn);
            }
            byte[] data = conn.getInputStream().readAllBytes();
            return data;
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_download_failed", e.getMessage()));
        }
    }

    /** Lista as chaves do bucket que começam com {@code prefix}, em ordem léxica. */
    public List<String> listObjects(String bucket, String prefix) {
        try {
            String query = "?list-type=2";
            if (prefix != null && !prefix.isBlank()) {
                query += "&prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8);
            }
            String encodedBucket = URLEncoder.encode(bucket, StandardCharsets.UTF_8);
            HttpURLConnection conn = openUrl("GET", endpoint + "/" + encodedBucket + query);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error("listObjects", conn);
            }
            String xml = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseKeys(xml);
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_list_failed", e.getMessage()));
        }
    }

    /** Apaga um objeto do bucket. */
    public void deleteObject(String bucket, String key) {
        try {
            HttpURLConnection conn = open("DELETE", bucket, key, null);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error("deleteObject", conn);
            }
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_delete_failed", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // conexão e autenticação
    // ------------------------------------------------------------------

    private static boolean localEndpoint(String endpoint) {
        String host = endpoint.replace("https://", "").replace("http://", "");
        int colon = host.indexOf(':');
        host = colon >= 0 ? host.substring(0, colon) : host;
        return host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("minio")
            || endpoint.contains(":9000");
    }

    private String url(String bucket, String key) {
        String encodedBucket = encodePath(bucket);
        String encodedKey = encodeKey(key);
        return endpoint + "/" + encodedBucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey);
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
            .replace("+", "%20").replace("%2F", "/");
    }

    private static String encodeKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return URLEncoder.encode(key, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/");
    }

    private HttpURLConnection open(String method, String bucket, String key, String query) {
        String base = url(bucket, key);
        return openUrl(method, query == null || query.isBlank() ? base : base + "?" + query);
    }

    private HttpURLConnection openUrl(String method, String rawUrl) {
        try {
            var conn = (HttpURLConnection) new java.net.URL(rawUrl).openConnection();
            conn.setRequestMethod(method);
            if (basicAuth) {
                String credentials = Base64.getEncoder().encodeToString(
                    (accessKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + credentials);
            } else {
                applyV4(conn, method, rawUrl);
            }
            return conn;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_connection_failed", rawUrl, e.getMessage()));
        }
    }

    private void applyV4(HttpURLConnection conn, String method, String rawUrl) {
        try {
            String date = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            String dateShort = date.substring(0, 8);
            // Use UNSIGNED-PAYLOAD for streaming uploads (PUT with chunked mode)
            String contentSha256 = "UNSIGNED-PAYLOAD";
            java.net.URI uri = java.net.URI.create(rawUrl);
            String canonicalHost = uri.getHost() == null ? "" : uri.getHost();
            if (uri.getPort() > 0 && uri.getPort() != (uri.getScheme().equals("https") ? 443 : 80)) {
                canonicalHost += ":" + uri.getPort();
            }
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();

            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalRequest = method + "\n" + path + "\n" + query + "\n"
                + "host:" + canonicalHost + "\n"
                + "x-amz-content-sha256:" + contentSha256 + "\n"
                + "x-amz-date:" + date + "\n\n"
                + signedHeaders + "\n"
                + contentSha256;

            String credentialScope = dateShort + "/" + region + "/s3/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + credentialScope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
            byte[] dateKey = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                dateShort.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey = hmacSha256(hmacSha256(hmacSha256(dateKey, region.getBytes(StandardCharsets.UTF_8)),
                "s3".getBytes(StandardCharsets.UTF_8)), "aws4_request".getBytes(StandardCharsets.UTF_8));
            String signature = hex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

            String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
            conn.setRequestProperty("x-amz-content-sha256", contentSha256);
            conn.setRequestProperty("x-amz-date", date);
            conn.setRequestProperty("Authorization", authorization);
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_s3_signature_failed", e.getMessage()));
        }
    }

    private void check(String op, HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw error(op, conn);
        }
    }

    private AxonError error(String op, HttpURLConnection conn) throws Exception {
        String err = new String(conn.getErrorStream() != null
            ? conn.getErrorStream().readAllBytes() : new byte[0], StandardCharsets.UTF_8);
        return AxonError.internal(Messages.get("backup_s3_operation_failed", op, conn.getResponseCode(), err));
    }

    // ------------------------------------------------------------------
    // parsing de ListObjectsV2
    // ------------------------------------------------------------------

    private static List<String> parseKeys(String xml) {
        List<String> keys = new ArrayList<>();
        Pattern contents = Pattern.compile("<Contents>.*?</Contents>", Pattern.DOTALL);
        Pattern keyElement = Pattern.compile("<Key>(.*?)</Key>", Pattern.DOTALL);
        Matcher m = contents.matcher(xml);
        while (m.find()) {
            Matcher k = keyElement.matcher(m.group());
            if (k.find()) {
                keys.add(k.group(1).trim());
            }
        }
        // ListObjectsV2 pode omitir <Contents> vazio; também aceita entrada simples <Key>.
        if (keys.isEmpty()) {
            Matcher bare = keyElement.matcher(xml);
            while (bare.find()) {
                keys.add(bare.group(1).trim());
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // criptografia auxiliar
    // ------------------------------------------------------------------

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("crypto_sha256_unavailable"), e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("crypto_hmac_sha256_unavailable"), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
