package com.axonbase.server;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.backup.S3Encryption;
import com.axonbase.core.backup.StreamEncryption;
import com.axonbase.core.security.Authz;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Export e restore de backup em streaming ({@code /admin/v1/backups/export} e
 * {@code /admin/v1/backups/restore}). O corpo nunca é montado em memória: o export varre o backend
 * com {@code scanPrefix} e escreve direto na resposta; o restore lê o corpo linha a linha.
 */
final class BackupStreamApi {

    private static final int SNIFF = 128;
    /** Maior linha aceita no restore (uma chave com valor em Base64). */
    static final int MAX_LINE_BYTES = 8 << 20;
    /** Maior corpo aceito no restore. */
    static final long MAX_BODY_BYTES = 64L << 30;

    private BackupStreamApi() {
    }

    /** Atende as duas rotas; devolve {@code false} quando a requisição não é uma delas. */
    static boolean handle(AxonServer srv, AdminV1Servlet api, String method, java.util.List<String> seg,
                          HttpServletRequest req, HttpServletResponse resp, AdminV1Servlet.Caller c)
            throws IOException {
        if (seg.size() != 2 || !seg.get(0).equals("backups")) {
            return false;
        }
        if (method.equals("GET") && seg.get(1).equals("export")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            export(srv, req, resp);
            return true;
        }
        if (method.equals("POST") && seg.get(1).equals("restore")) {
            api.needRoot(c, Authz.Role.ADMIN);
            restore(srv, req, resp);
            return true;
        }
        return false;
    }

    private static void export(AxonServer srv, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String type = req.getParameter("type") == null ? "full" : req.getParameter("type").toLowerCase();
        if (!type.equals("full") && !type.equals("incremental")) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_export_type"));
        }
        long since = 0;
        if (type.equals("incremental")) {
            try {
                since = Long.parseLong(String.valueOf(req.getParameter("since")).trim());
            } catch (NumberFormatException e) {
                since = 0;
            }
            if (since <= 0) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_since_version"));
            }
        }
        // marca d'água lida ANTES do dump: o próximo incremental parte dela
        long watermark = srv.ds().backupVersion();
        boolean incremental = type.equals("incremental") && srv.ds().incrementalSupported();
        String key = srv.config().s3EncryptKey();
        boolean encrypt = key != null && !key.isBlank();

        resp.setStatus(200);
        resp.setContentType("application/octet-stream");
        resp.setHeader("X-Axon-Backup-Type", incremental ? "incremental" : "full");
        resp.setHeader("X-Axon-Backup-Version", String.valueOf(watermark));
        resp.setHeader("Content-Disposition", "attachment; filename=\"axonbase-" + (incremental ? "incremental" : "full")
            + "-" + watermark + ".axbak\"");
        resp.setHeader("Cache-Control", "no-store");
        OutputStream sink = resp.getOutputStream();
        try (OutputStream out = encrypt ? StreamEncryption.encrypting(sink, key) : new NoCloseOutput(sink)) {
            srv.ds().backupTo(out, incremental ? since : 0);
        } catch (IOException | RuntimeException e) {
            // o status 200 já saiu: só resta registrar e abortar a conexão
            AxonLog.log("error", "backup_export_failed", e.getMessage() == null ? e.toString() : e.getMessage());
            throw new IOException(e);
        }
    }

    private static void restore(AxonServer srv, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"true".equals(req.getParameter("confirm"))) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_restore_confirm"));
        }
        if (!"local".equals(srv.clusterStatus().status().clusterId())) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_restore_cluster"));
        }
        boolean forced = "true".equals(req.getParameter("force"));
        if (!forced && srv.ds().hasRecords()) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_restore_not_empty"));
        }
        BufferedInputStream body = new BufferedInputStream(
            new LimitedInput(req.getInputStream(), MAX_BODY_BYTES), 1 << 16);
        long restoredKeys;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("axonbase-restore-", ".axbak");
        try {
            // 1) grava o corpo decifrado em arquivo temporário: consumir TUDO valida a cadeia AES-GCM
            //    (inclusive o último bloco) e o limite de linha, antes de qualquer escrita no armazenamento
            try (InputStream plain = openPlain(body, srv.config().s3EncryptKey())) {
                copyValidating(plain, temp);
            } catch (AxonError | IOException | IllegalArgumentException e) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST",
                    Messages.get("admin_backup_stream_invalid", e.getMessage()));
            }
            // 2) valida o cabeçalho ainda sem tocar nos dados
            try (BufferedReader head = java.nio.file.Files.newBufferedReader(temp, StandardCharsets.UTF_8)) {
                if (!"--axonbase-backup-v1--".equals(head.readLine())) {
                    throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_backup_stream_invalid",
                        Messages.get("stmt_cluster_backup_invalid")));
                }
            }
            // 3) só agora limpa e restaura, lendo do arquivo
            try (BufferedReader reader = java.nio.file.Files.newBufferedReader(temp, StandardCharsets.UTF_8)) {
                restoredKeys = srv.ds().restoreCluster(reader);
            } catch (AxonError | IOException | IllegalArgumentException e) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST",
                    Messages.get("admin_backup_stream_invalid", e.getMessage()));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
            // também no caminho de erro: uma restauração interrompida não pode deixar a base sem root
            if (srv.auth() != null) {
                srv.auth().ensureRoot(srv.config().user(), srv.config().password());
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("restored", true, "restoredKeys", restoredKeys));
    }

    /** Copia validando o tamanho de cada linha; lança IOException se alguma passar do limite. */
    private static void copyValidating(InputStream in, java.nio.file.Path target) throws IOException {
        byte[] buf = new byte[1 << 16];
        int lineBytes = 0;
        try (OutputStream out = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(target), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        lineBytes = 0;
                    } else if (++lineBytes > MAX_LINE_BYTES) {
                        throw new IOException(Messages.get("admin_restore_line_too_long", MAX_LINE_BYTES));
                    }
                }
                out.write(buf, 0, n);
            }
        }
    }

    /** Limita o total de bytes lidos do corpo. */
    private static final class LimitedInput extends java.io.FilterInputStream {
        private long left;

        LimitedInput(InputStream in, long max) {
            super(in);
            this.left = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) {
                throw new IOException(Messages.get("admin_restore_body_too_large"));
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (left -= n) < 0) {
                throw new IOException(Messages.get("admin_restore_body_too_large"));
            }
            return n;
        }
    }

    /**
     * Detecta o cabeçalho e devolve o fluxo de texto claro: blocos v2 são decifrados em streaming; o
     * formato v1 criptografado (um único Base64) é lido por inteiro e decifrado; o plano passa direto.
     */
    private static InputStream openPlain(BufferedInputStream body, String password) throws IOException {
        body.mark(SNIFF);
        byte[] head = body.readNBytes(SNIFF);
        body.reset();
        String text = new String(head, StandardCharsets.US_ASCII);
        if (text.startsWith(StreamEncryption.HEADER)) {
            body.skipNBytes(StreamEncryption.HEADER.length() + 1L);
            return StreamEncryption.decrypting(body, password);
        }
        if (text.startsWith(S3Encryption.ENCRYPTED_HEADER)) {
            return new java.io.ByteArrayInputStream(S3Encryption.decryptBytes(body.readAllBytes(), password));
        }
        return body;
    }

    /** Impede que o fim do dump feche a resposta antes da hora. */
    private static final class NoCloseOutput extends OutputStream {
        private final OutputStream delegate;

        NoCloseOutput(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }
}
