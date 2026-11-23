package com.axonbase.server;

import com.axonbase.common.Messages;
import com.axonbase.core.backup.S3SyncManager;
import com.axonbase.core.security.Authz;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rotas de {@code /admin/v1} que dependem de serviços opcionais (backup). */
final class AdminV1Extensions {

    private AdminV1Extensions() {
    }

    /** Devolve a resposta da rota, ou {@code null} quando nenhuma extensão a atende. */
    static AdminV1Response route(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                                 HttpServletRequest req, AdminV1Servlet.Caller c) throws IOException {
        if (seg.isEmpty() || !seg.get(0).equals("backups")) {
            return null;
        }
        int n = seg.size();
        if (method.equals("GET") && n == 1) {
            api.needRoot(c, Authz.Role.OPERATOR);
            return AdminV1Servlet.ok(list(srv.backups()));
        }
        if (method.equals("GET") && n == 2 && seg.get(1).equals("status")) {
            api.needRoot(c, Authz.Role.OPERATOR);
            return AdminV1Servlet.ok(status(srv.backups()));
        }
        if (method.equals("POST") && n == 1) {
            api.needRoot(c, Authz.Role.OPERATOR);
            return AdminV1Servlet.accepted(trigger(srv, api, req));
        }
        if (method.equals("POST") && n == 3 && seg.get(2).equals("restore")) {
            api.needRoot(c, Authz.Role.ADMIN);
            return AdminV1Servlet.ok(restore(srv, api, seg.get(1), req));
        }
        return null;
    }

    private static AxonValue list(S3SyncManager m) {
        List<Object> out = new ArrayList<>();
        if (m != null) {
            for (S3SyncManager.BackupEntry e : m.listBackupEntries()) {
                out.add(AdminJson.obj("name", e.name(), "sizeBytes", e.sizeBytes(),
                    "createdAt", AdminJson.iso(e.createdAtMillis()), "type", e.type()));
            }
        }
        return AdminJson.of(out);
    }

    private static AxonValue status(S3SyncManager m) {
        if (m == null) {
            return AdminJson.obj("configured", false, "running", false, "lastSuccessAt", null, "lastErrorAt", null,
                "lastError", null, "lastDurationMs", null, "lastSizeBytes", null, "lastType", null,
                "nextRunAt", null);
        }
        S3SyncManager.Status s = m.status();
        return AdminJson.obj("configured", true, "running", s.running(),
            "lastSuccessAt", s.lastSuccessAtMillis() == null ? null : AdminJson.iso(s.lastSuccessAtMillis()),
            "lastErrorAt", s.lastErrorAtMillis() == null ? null : AdminJson.iso(s.lastErrorAtMillis()),
            "lastError", s.lastError(),
            "lastDurationMs", s.lastSuccessAtMillis() == null && s.lastErrorAtMillis() == null ? null : s.lastDurationMs(),
            "lastSizeBytes", s.lastSuccessAtMillis() == null ? null : s.lastSizeBytes(),
            "lastType", s.lastType(),
            "nextRunAt", s.nextRunAtMillis() == null ? null : AdminJson.iso(s.nextRunAtMillis()));
    }

    private static AxonValue trigger(AxonServer srv, AdminV1Servlet api, HttpServletRequest req) throws IOException {
        Map<String, AxonValue> body = api.object(api.body(req));
        String type = body.containsKey("type") ? api.text(body, "type", true).toLowerCase() : "auto";
        if (!type.equals("auto") && !type.equals("full")) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_backup_type"));
        }
        S3SyncManager m = srv.backups();
        if (m == null) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_backup_unconfigured"));
        }
        String runId = m.triggerAsync(type.equals("full"));
        if (runId == null) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_backup_running"));
        }
        return AdminJson.obj("runId", runId);
    }

    private static AxonValue restore(AxonServer srv, AdminV1Servlet api, String name, HttpServletRequest req)
            throws IOException {
        Map<String, AxonValue> body = api.object(api.body(req));
        AxonValue confirm = body.get("confirm");
        if (confirm == null || !confirm.isBool() || !confirm.asBool()) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", Messages.get("admin_restore_confirm"));
        }
        S3SyncManager m = srv.backups();
        if (m == null) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_backup_unconfigured"));
        }
        if (m.listBackupEntries().stream().noneMatch(e -> e.name().equals(name))) {
            throw AdminV1Servlet.notFound();
        }
        if (!"local".equals(srv.clusterStatus().status().clusterId())) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_restore_cluster"));
        }
        AxonValue force = body.get("force");
        boolean forced = force != null && force.isBool() && force.asBool();
        if (!forced && srv.ds().hasRecords()) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", Messages.get("admin_restore_not_empty"));
        }
        if (!m.restoreTo(name)) {
            throw new AdminV1Servlet.ApiError(500, "INTERNAL", Messages.get("admin_restore_failed"));
        }
        if (srv.auth() != null) {
            srv.auth().ensureRoot(srv.config().user(), srv.config().password());
        }
        return AdminJson.obj("restored", true, "name", name);
    }
}
