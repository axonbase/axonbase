package com.axonbase.server;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.partition.ColdBackups;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.backup.StreamEncryption;
import com.axonbase.core.journal.PitrRestore;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.security.Authz;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.PhysicalFiles;
import com.axonbase.core.storage.RocksDbBackend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backup físico pela API administrativa ({@code /admin/v1/backups/physical}), sobre o {@code BackupEngine} do
 * RocksDB: criar, listar, verificar, aplicar retenção e restaurar. O restore troca a base dentro do processo,
 * sem reiniciar o servidor. Exige armazenamento RocksDB. Em cluster, o backup é criado num seguidor ou learner
 * (listar, verificar, baixar e aplicar retenção valem em qualquer nó); restaurar e importar só em nó único.
 *
 * <p>O diretório de backup é {@code AXON_BACKUP_DIR} (ou a propriedade {@code axon.backup.dir}); sem isso, fica
 * em {@code <dados>/.physical-backups}, dentro do mesmo volume e ignorado pelo RocksDB.</p>
 */
final class PhysicalBackupApi {

    /** Quanto o restore espera as operações em curso terminarem antes de desistir. */
    static final long QUIESCE_TIMEOUT_MS = 60_000L;

    private static final Object LOCK = new Object();

    private PhysicalBackupApi() {
    }

    static boolean handle(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                          HttpServletRequest req, HttpServletResponse resp, AdminV1Servlet.Caller c)
            throws IOException {
        if (seg.size() < 2 || !seg.get(0).equals("backups") || !seg.get(1).equals("physical")) {
            return false;
        }
        if (seg.size() == 2 && method.equals("GET")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            list(srv, resp);
            return true;
        }
        if (seg.size() == 2 && method.equals("POST")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            create(srv, resp);
            return true;
        }
        if (seg.size() == 2 && method.equals("DELETE")) {
            api.needRoot(c, Authz.Role.ADMIN);
            purge(srv, req, resp);
            return true;
        }
        if (seg.size() == 4 && method.equals("GET") && seg.get(3).equals("files")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            files(srv, id(seg.get(2)), req, resp);
            return true;
        }
        if (seg.size() == 3 && seg.get(2).equals("files") && method.equals("GET")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            readFile(srv, req, resp);
            return true;
        }
        if (seg.size() == 3 && seg.get(2).equals("files") && method.equals("PUT")) {
            api.needRoot(c, Authz.Role.ADMIN);
            writeFile(srv, req, resp);
            return true;
        }
        if (seg.size() == 4 && seg.get(2).equals("imports") && method.equals("DELETE")) {
            api.needRoot(c, Authz.Role.ADMIN);
            deleteImport(srv, seg.get(3), resp);
            return true;
        }
        if (seg.size() == 4 && method.equals("POST") && seg.get(3).equals("verify")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            verify(srv, id(seg.get(2)), resp);
            return true;
        }
        if (seg.size() == 4 && method.equals("POST") && seg.get(3).equals("restore")) {
            api.needRoot(c, Authz.Role.ADMIN);
            restore(srv, id(seg.get(2)), req, resp);
            return true;
        }
        return false;
    }

    private static int id(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("physical_not_found", -1));
        }
    }

    static RocksDbBackend rocks(AxonServer srv) {
        RocksDbBackend rocks = srv.ds().rocksBackend()
            .orElseThrow(() -> new AdminV1Servlet.ApiError(409, "PHYSICAL_UNSUPPORTED",
                Messages.get("physical_unsupported")));
        return rocks;
    }

    private static boolean clustered(AxonServer srv) {
        return !"local".equals(srv.clusterStatus().status().clusterId());
    }

    /** Restaurar ou importar mexeria na base de um membro por fora do consenso. */
    static void requireStandalone(AxonServer srv) {
        if (clustered(srv)) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("physical_cluster_restore"));
        }
    }

    /** Criar o backup pesa no disco: em cluster fica com uma réplica, para não atrasar as escritas do líder. */
    private static void requireBackupSource(AxonServer srv) {
        var role = srv.clusterStatus().status().role();
        if (clustered(srv) && role != ClusterRole.FOLLOWER && role != ClusterRole.LEARNER) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("physical_cluster"));
        }
    }

    static Path backupDir(RocksDbBackend rocks) {
        String configured = System.getenv("AXON_BACKUP_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("axon.backup.dir");
        }
        return configured == null || configured.isBlank()
            ? rocks.directory().resolve(".physical-backups") : Path.of(configured);
    }

    private static Map<String, Object> toJson(PhysicalBackups.Info i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", i.id());
        m.put("createdAt", AdminJson.iso(i.timestampSeconds() * 1000L));
        m.put("sizeBytes", i.sizeBytes());
        m.put("files", i.files());
        m.put("version", i.version());
        if (!i.watermark().equals(CommitWatermark.NONE)) {
            // só backups com carimbo servem de base para restaurar a um instante
            m.put("stampedAt", AdminJson.iso(i.watermark().stamp().physical()));
            m.put("journalIndex", i.watermark().index());
        }
        return m;
    }

    /** Os backups do diretório do motor ou de uma área de importação. */
    static List<PhysicalBackups.Info> backups(RocksDbBackend rocks, String source) {
        Path dir = sourceDir(rocks, source, true);
        synchronized (LOCK) {
            try (PhysicalBackups pb = PhysicalBackups.open(dir)) {
                return pb.list();
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            }
        }
    }

    /** O cabeçalho opcional {@code X-Axon-Size}, ou -1. */
    static long declaredSize(HttpServletRequest req) {
        String header = req.getHeader("X-Axon-Size");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "X-Axon-Size");
        }
    }

    private static void list(AxonServer srv, HttpServletResponse resp) throws IOException {
        RocksDbBackend rocks = rocks(srv);
        List<Object> out = new ArrayList<>();
        synchronized (LOCK) {
            try (PhysicalBackups pb = PhysicalBackups.open(backupDir(rocks))) {
                pb.list().forEach(i -> out.add(toJson(i)));
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("backups", out));
    }

    private static void create(AxonServer srv, HttpServletResponse resp) throws IOException {
        RocksDbBackend rocks = rocks(srv);
        requireBackupSource(srv);
        PhysicalBackups.Info info;
        synchronized (LOCK) {
            Path dir = backupDir(rocks);
            try (PhysicalBackups pb = PhysicalBackups.open(dir)) {
                // os objetos frios não são copiados: o backup os referencia e os bloqueia
                info = ColdBackups.createPhysical(srv.ds(), pb, rocks, dir);
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            } catch (AxonError e) {
                throw new AdminV1Servlet.ApiError(409, "COLD_HOLD_FAILED", e.getMessage());
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.of(toJson(info)));
    }

    private static void verify(AxonServer srv, int id, HttpServletResponse resp) throws IOException {
        RocksDbBackend rocks = rocks(srv);
        boolean ok;
        synchronized (LOCK) {
            try (PhysicalBackups pb = PhysicalBackups.open(backupDir(rocks))) {
                requireExists(pb, id);
                ok = pb.verify(id);
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("id", id, "ok", ok));
    }

    private static void purge(AxonServer srv, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        RocksDbBackend rocks = rocks(srv);
        int keep;
        try {
            keep = Integer.parseInt(String.valueOf(req.getParameter("keep")).trim());
        } catch (NumberFormatException e) {
            keep = -1;
        }
        if (keep < 1) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "keep >= 1");
        }
        List<Object> out = new ArrayList<>();
        synchronized (LOCK) {
            Path dir = backupDir(rocks);
            try (PhysicalBackups pb = PhysicalBackups.open(dir)) {
                pb.purgeKeeping(keep);
                ColdBackups.releasePurged(srv.ds(), dir, pb.list());
                pb.list().forEach(i -> out.add(toJson(i)));
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            } catch (AxonError e) {
                throw new AdminV1Servlet.ApiError(409, "COLD_HOLD_FAILED", e.getMessage());
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("backups", out));
    }

    private static void restore(AxonServer srv, int id, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        requireStandalone(srv);
        if (!"true".equals(req.getParameter("confirm"))) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_restore_confirm"));
        }
        RocksDbBackend rocks = rocks(srv);
        String source = req.getParameter("source");
        String at = req.getParameter("at");
        Path dir = sourceDir(rocks, source, true);
        Path previous;
        long version;
        PitrRestore.Prepared pitr = null;
        synchronized (LOCK) {
            try {
                try (PhysicalBackups pb = PhysicalBackups.open(dir)) {
                    requireExists(pb, id);
                    version = pb.list().stream().filter(i -> i.id() == id).findFirst().orElseThrow().version();
                }
                // 1) restaura num diretório vizinho e prova que abre, com o servidor atendendo normalmente; com
                // "at", reaplica também o journal até o instante pedido
                Path prepared;
                if (at == null) {
                    prepared = PhysicalBackups.prepareRestore(dir, id, rocks.directory());
                } else {
                    pitr = PitrApi.prepare(srv, rocks, dir, id, source, at);
                    prepared = pitr.directory();
                }
                try {
                    ColdBackups.requireRestorable(srv.ds(), prepared);
                } catch (AxonError missing) {
                    PhysicalBackups.discardPrepared(rocks.directory());
                    throw new AdminV1Servlet.ApiError(409, "COLD_MISSING", missing.getMessage());
                }
                // 2) segura as operações, troca a base e reabre; desiste sem tocar na base se não esvaziar a tempo
                try {
                    previous = rocks.replaceWith(prepared, QUIESCE_TIMEOUT_MS);
                } catch (RocksDbBackend.SwapTimeoutException busy) {
                    PhysicalBackups.discardPrepared(rocks.directory());
                    throw new AdminV1Servlet.ApiError(409, "BUSY", Messages.get("physical_busy"));
                }
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            }
            // 3) a base mudou por baixo do motor: refaz o catálogo e garante o usuário de arranque
            srv.ds().reloadFromBackend();
            if (srv.auth() != null) {
                srv.auth().ensureRoot(srv.config().user(), srv.config().password());
            }
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("restored", true);
        body.put("id", id);
        body.put("version", version);
        body.put("previous", previous.getFileName().toString());
        if (pitr != null) {
            body.put("at", at);
            body.put("appliedBatches", pitr.appliedBatches());
            body.put("restoredUntil", AdminJson.iso(pitr.position().stamp().physical()));
        }
        AdminV1Servlet.write(resp, 200, AdminJson.of(body));
    }

    // ---- transferência de arquivos ----

    /** O diretório de backup da origem pedida, ou o do próprio motor quando não há origem. */
    private static Path sourceDir(RocksDbBackend rocks, String source, boolean mustExist) {
        Path base = backupDir(rocks);
        try {
            Path dir = PhysicalFiles.importRoot(base, source);
            if (source != null && mustExist && !java.nio.file.Files.isDirectory(dir)) {
                throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("physical_source_not_found", source));
            }
            return dir;
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        }
    }

    private static void files(AxonServer srv, int id, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        RocksDbBackend rocks = rocks(srv);
        Path dir = sourceDir(rocks, req.getParameter("source"), true);
        List<Object> out = new ArrayList<>();
        long version;
        synchronized (LOCK) {
            try (PhysicalBackups pb = PhysicalBackups.open(dir)) {
                requireExists(pb, id);
                version = pb.list().stream().filter(i -> i.id() == id).findFirst().orElseThrow().version();
                for (PhysicalFiles.Entry f : PhysicalFiles.list(dir, id)) {
                    out.add(AdminJson.obj("path", f.path(), "sizeBytes", f.sizeBytes(), "shared", f.shared()));
                }
            } catch (PhysicalBackups.BackupFailedException e) {
                throw failed(e);
            }
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("id", id, "version", version, "files", out));
    }

    private static void readFile(AxonServer srv, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        RocksDbBackend rocks = rocks(srv);
        Path file;
        try {
            file = PhysicalFiles.resolve(backupDir(rocks), req.getParameter("source"), req.getParameter("path"));
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("physical_file_not_found",
                req.getParameter("path")));
        }
        String key = srv.config().s3EncryptKey();
        boolean encrypt = key != null && !key.isBlank();
        resp.setStatus(200);
        resp.setContentType("application/octet-stream");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Axon-Size", String.valueOf(java.nio.file.Files.size(file)));
        try (java.io.OutputStream out = encrypt
                ? StreamEncryption.encrypting(resp.getOutputStream(), key) : resp.getOutputStream();
             java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            in.transferTo(out);
        }
    }

    private static void writeFile(AxonServer srv, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        requireStandalone(srv);
        RocksDbBackend rocks = rocks(srv);
        String source = req.getParameter("source");
        String path = req.getParameter("path");
        if (source == null || source.isBlank()) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("physical_source_required"));
        }
        long declared = declaredSize(req);
        long written;
        try (java.io.InputStream plain = BackupStreamApi.openPlain(
                new java.io.BufferedInputStream(req.getInputStream(), 1 << 16), srv.config().s3EncryptKey())) {
            written = PhysicalFiles.write(backupDir(rocks), source, path, plain, declared);
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST",
                Messages.get("admin_backup_stream_invalid", e.getMessage()));
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("path", path, "sizeBytes", written));
    }

    private static void deleteImport(AxonServer srv, String source, HttpServletResponse resp) throws IOException {
        requireStandalone(srv);
        RocksDbBackend rocks = rocks(srv);
        try {
            sourceDir(rocks, source, true);
            synchronized (LOCK) {
                PhysicalFiles.deleteImport(backupDir(rocks), source);
            }
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("deleted", true, "source", source));
    }

    private static void requireExists(PhysicalBackups pb, int id) {
        if (pb.list().stream().noneMatch(i -> i.id() == id)) {
            throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("physical_not_found", id));
        }
    }

    private static AdminV1Servlet.ApiError failed(PhysicalBackups.BackupFailedException e) {
        return new AdminV1Servlet.ApiError(500, "BACKUP_FAILED", Messages.get("physical_failed", e.getMessage()));
    }
}
