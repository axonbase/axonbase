package com.axonbase.server;

import com.axonbase.common.Messages;
import com.axonbase.core.backup.StreamEncryption;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalSegments;
import com.axonbase.core.journal.PitrCoverage;
import com.axonbase.core.journal.PitrException;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.journal.PitrRestore;
import com.axonbase.core.journal.SegmentInfo;
import com.axonbase.core.security.Authz;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal de PITR (Point-In-Time Recovery) pela API administrativa ({@code /admin/v1/backups/journal}): situação,
 * janela alcançável por backup, selagem e transferência de segmentos, no mesmo estilo e com a mesma cifra opcional
 * dos arquivos do backup físico. A restauração a um instante é
 * {@code POST /admin/v1/backups/physical/{id}/restore?confirm=true&at=<instante ISO>}.
 */
final class PitrApi {

    private PitrApi() {
    }

    static boolean handle(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                          HttpServletRequest req, HttpServletResponse resp, AdminV1Servlet.Caller c)
            throws IOException {
        if (seg.size() < 2 || !seg.get(0).equals("backups") || !seg.get(1).equals("journal")) {
            return false;
        }
        if (seg.size() == 2 && method.equals("GET")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            status(srv, req, resp);
            return true;
        }
        if (seg.size() == 3 && method.equals("GET") && seg.get(2).equals("windows")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            windows(srv, req, resp);
            return true;
        }
        if (seg.size() == 3 && method.equals("POST") && seg.get(2).equals("seal")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            PhysicalBackupApi.rocks(srv);
            AdminV1Servlet.write(resp, 200, AdminJson.of(toJson(enabled(srv).seal())));
            return true;
        }
        if (seg.size() == 4 && seg.get(2).equals("segments")) {
            switch (method) {
                case "GET" -> {
                    api.needRoot(c, Authz.Role.OPERATOR);
                    readSegment(srv, seg.get(3), req, resp);
                }
                case "PUT" -> {
                    api.needRoot(c, Authz.Role.ADMIN);
                    writeSegment(srv, seg.get(3), req, resp);
                }
                case "DELETE" -> {
                    api.needRoot(c, Authz.Role.ADMIN);
                    deleteSegment(srv, seg.get(3), req, resp);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (seg.size() == 4 && method.equals("DELETE") && seg.get(2).equals("imports")) {
            api.needRoot(c, Authz.Role.ADMIN);
            deleteImport(srv, seg.get(3), resp);
            return true;
        }
        return false;
    }

    /** Prepara a base no instante pedido, sem tocar na ativa. Quem chama faz a troca. */
    static PitrRestore.Prepared prepare(AxonServer srv, RocksDbBackend rocks, Path backupDir, int id, String source,
                                        String at) {
        Instant instant;
        long millis;
        try {
            instant = Instant.parse(at.trim());
            millis = instant.toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("pitr_invalid_instant", at));
        }
        Path journalDir;
        if (source == null) {
            PitrJournal journal = enabled(srv);
            journal.seal(); // o que já foi confirmado precisa estar num segmento fechado
            journalDir = journal.directory();
        } else {
            journalDir = root(srv, rocks, source, true);
        }
        try {
            return PitrRestore.prepare(backupDir, id, journalDir, rocks.directory(),
                HlcTimestamp.endOfMillisecond(millis));
        } catch (PitrException e) {
            throw toApiError(e, id, instant);
        }
    }

    private static AdminV1Servlet.ApiError toApiError(PitrException e, int id, Instant instant) {
        return switch (e.reason()) {
            case NO_BACKUP -> new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("physical_not_found", id));
            case NO_BASE_STAMP -> new AdminV1Servlet.ApiError(409, "PITR_NO_BASE_STAMP",
                Messages.get("pitr_no_base_stamp", id));
            case BASE_TOO_NEW -> new AdminV1Servlet.ApiError(409, "PITR_BASE_TOO_NEW",
                Messages.get("pitr_base_too_new", id, instant.toString()));
            case UNREACHABLE -> {
                String latest = String.valueOf(AdminJson.iso(PitrCoverage.latestMillisUpTo(e.latest())));
                yield new AdminV1Servlet.ApiError(409, "PITR_UNREACHABLE",
                    Messages.get("pitr_unreachable", id, latest), Map.of("latest", latest));
            }
            case BAD_JOURNAL -> new AdminV1Servlet.ApiError(409, "PITR_BAD_JOURNAL",
                Messages.get("pitr_bad_journal", String.valueOf(e.getMessage())));
        };
    }

    private static PitrJournal enabled(AxonServer srv) {
        PitrJournal journal = srv.pitr();
        if (journal == null) {
            throw new AdminV1Servlet.ApiError(409, "PITR_DISABLED", Messages.get("pitr_disabled"));
        }
        return journal;
    }

    /** O diretório de segmentos pedido: o do journal ligado ou a área de importação da origem. */
    private static Path root(AxonServer srv, RocksDbBackend rocks, String source, boolean mustExist) {
        PitrJournal journal = srv.pitr();
        Path base = journal != null ? journal.directory() : PitrSettings.fromEnvironment().directoryFor(rocks);
        Path dir;
        try {
            dir = JournalSegments.importRoot(base, source);
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        }
        if (source != null && mustExist && !Files.isDirectory(dir)) {
            throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("physical_source_not_found", source));
        }
        return dir;
    }

    private static Path segmentFile(AxonServer srv, RocksDbBackend rocks, String source, String name) {
        Path dir = root(srv, rocks, source, false);
        try {
            return JournalSegments.resolve(dir, null, name);
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        }
    }

    private static List<SegmentInfo> segments(AxonServer srv, RocksDbBackend rocks, String source)
            throws IOException {
        if (source != null) {
            return JournalSegments.list(root(srv, rocks, source, true));
        }
        PitrJournal journal = srv.pitr();
        return journal == null ? List.of() : journal.segments();
    }

    private static void status(AxonServer srv, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        PitrJournal journal = srv.pitr();
        List<Object> list = new ArrayList<>();
        segments(srv, rocks, req.getParameter("source")).forEach(s -> list.add(toJson(s)));
        AdminV1Servlet.write(resp, 200, AdminJson.obj("enabled", journal != null,
            "droppedBatches", journal == null ? 0L : journal.droppedBatches(),
            "writeErrors", journal == null ? 0L : journal.writeErrors(),
            "pendingBatches", journal == null ? 0 : journal.pendingBatches(),
            "segments", list));
    }

    /** Para cada backup, de quando até quando os segmentos fechados permitem restaurar. */
    private static void windows(AxonServer srv, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        String source = req.getParameter("source");
        List<SegmentInfo> segments = segments(srv, rocks, source);
        Path journalDir = root(srv, rocks, source, false);
        List<Object> out = new ArrayList<>();
        for (PhysicalBackups.Info backup : PhysicalBackupApi.backups(rocks, source)) {
            if (backup.watermark().equals(CommitWatermark.NONE)) {
                continue;
            }
            PitrCoverage.Window window = PitrCoverage.window(journalDir, segments, backup.watermark());
            long latest = window.latestMillis();
            // "to" nulo: ainda não há instante inteiro coberto depois do backup
            out.add(AdminJson.obj("backupId", backup.id(), "from", AdminJson.iso(window.from().physical()),
                "to", latest < window.from().physical() ? null : AdminJson.iso(latest)));
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("windows", out));
    }

    private static void readSegment(AxonServer srv, String name, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        Path file = segmentFile(srv, rocks, req.getParameter("source"), name);
        String key = srv.config().s3EncryptKey();
        boolean encrypt = key != null && !key.isBlank();
        // abre antes de responder: se a retenção apagar o arquivo, a resposta é 404 e não um corpo vazio
        try (java.nio.channels.FileChannel channel = open(file, name)) {
            resp.setStatus(200);
            resp.setContentType("application/octet-stream");
            resp.setHeader("Cache-Control", "no-store");
            resp.setHeader("X-Axon-Size", String.valueOf(channel.size()));
            try (java.io.OutputStream out = encrypt
                    ? StreamEncryption.encrypting(resp.getOutputStream(), key) : resp.getOutputStream()) {
                java.nio.channels.Channels.newInputStream(channel).transferTo(out);
            }
        }
    }

    private static java.nio.channels.FileChannel open(Path file, String name) throws IOException {
        try {
            if (Files.isRegularFile(file)) {
                return java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ);
            }
        } catch (java.nio.file.NoSuchFileException removed) {
            // apagado entre a checagem e a abertura
        }
        throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("pitr_segment_not_found", name));
    }

    private static void writeSegment(AxonServer srv, String name, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        PhysicalBackupApi.requireStandalone(srv);
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        String source = req.getParameter("source");
        if (source == null || source.isBlank()) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("physical_source_required"));
        }
        segmentFile(srv, rocks, source, name); // valida o nome e a origem antes de ler o corpo
        long declared = PhysicalBackupApi.declaredSize(req);
        Path journalDir = root(srv, rocks, null, false);
        SegmentInfo written;
        try (java.io.InputStream plain = BackupStreamApi.openPlain(
                new java.io.BufferedInputStream(req.getInputStream(), 1 << 16), srv.config().s3EncryptKey())) {
            written = JournalSegments.write(journalDir, source, name, plain, declared);
        } catch (IllegalArgumentException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST",
                Messages.get("admin_backup_stream_invalid", e.getMessage()));
        }
        AdminV1Servlet.write(resp, 200, AdminJson.of(toJson(written)));
    }

    private static void deleteSegment(AxonServer srv, String name, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        PhysicalBackupApi.requireStandalone(srv);
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        String source = req.getParameter("source");
        Path file = segmentFile(srv, rocks, source, name);
        PitrJournal journal = srv.pitr();
        boolean existed = source == null && journal != null ? journal.delete(name) : Files.deleteIfExists(file);
        if (!existed) {
            throw new AdminV1Servlet.ApiError(404, "NOT_FOUND", Messages.get("pitr_segment_not_found", name));
        }
        AdminV1Servlet.write(resp, 200, AdminJson.obj("deleted", true, "name", name));
    }

    private static void deleteImport(AxonServer srv, String source, HttpServletResponse resp) throws IOException {
        PhysicalBackupApi.requireStandalone(srv);
        RocksDbBackend rocks = PhysicalBackupApi.rocks(srv);
        root(srv, rocks, source, true);
        JournalSegments.deleteImport(root(srv, rocks, null, false), source);
        AdminV1Servlet.write(resp, 200, AdminJson.obj("deleted", true, "source", source));
    }

    private static Map<String, Object> toJson(SegmentInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("sizeBytes", s.sizeBytes());
        m.put("batches", s.batches());
        m.put("firstAt", AdminJson.iso(s.firstStamp().physical()));
        m.put("lastAt", AdminJson.iso(s.lastStamp().physical()));
        m.put("firstIndex", s.firstIndex());
        m.put("lastIndex", s.lastIndex());
        m.put("completeAt", AdminJson.iso(s.complete().stamp().physical()));
        m.put("completeIndex", s.complete().index());
        m.put("recovered", s.recovered());
        return m;
    }
}
