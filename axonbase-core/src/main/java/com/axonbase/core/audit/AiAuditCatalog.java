package com.axonbase.core.audit;

import com.axonbase.core.storage.KvBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Catálogo de auditoria: mantém definições de audit, casos pendentes,
 * histórico e usuários bloqueados. Persiste no KV para sobreviver a restart.
 */
public final class AiAuditCatalog {

    private static final String PREFIX_CASE = "!audit_case|";
    private static final String PREFIX_HISTORY = "!audit_history|";
    private static final String PREFIX_BLOCKED = "!blocked_user|";

    private final KvBackend kv;
    private final ConcurrentMap<String, AuditCase> pendingCases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AuditCase> history = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BlockedUser> blockedUsers = new ConcurrentHashMap<>();

    public AiAuditCatalog(KvBackend kv) {
        this.kv = kv;
        restore();
    }

    // ---- case management ----

    public void addCase(String hash, AuditCase auditCase) {
        pendingCases.put(hash, auditCase);
        kv.put(PREFIX_CASE + hash, AxonJson.encode(toJson(auditCase)));
    }

    public void addEvent(String hash, AuditCaseEvent event) {
        AuditCase auditCase = pendingCases.get(hash);
        if (auditCase != null) {
            auditCase.addEvent(event.type(), event.userId(), event.note());
            kv.put(PREFIX_CASE + hash, AxonJson.encode(toJson(auditCase)));
        }
    }

    public AuditCase getCase(String hash) {
        AuditCase ac = pendingCases.get(hash);
        if (ac != null) return ac;
        return history.get(hash);
    }

    public List<AuditCase> listCases() {
        return List.copyOf(pendingCases.values());
    }

    public List<AuditCase> listHistory() {
        return List.copyOf(history.values());
    }

    public void resolveCase(String hash, String status, String by, String note) {
        AuditCase ac = pendingCases.remove(hash);
        if (ac != null) {
            ac.resolve(status, by, note);
            history.put(hash, ac);
            kv.delete(PREFIX_CASE + hash);
            kv.put(PREFIX_HISTORY + hash, AxonJson.encode(toJson(ac)));
        }
    }

    // ---- blocked users ----

    public void blockUser(String userId, String auditName, String sql, String reason, String blockedBy) {
        BlockedUser bu = new BlockedUser(userId, auditName, sql, reason, System.currentTimeMillis(), blockedBy);
        blockedUsers.put(userId, bu);
        kv.put(PREFIX_BLOCKED + userId, AxonJson.encode(toJson(bu)));
    }

    public void unblockUser(String userId) {
        blockedUsers.remove(userId);
        kv.delete(PREFIX_BLOCKED + userId);
    }

    public boolean isBlocked(String userId) {
        return blockedUsers.containsKey(userId);
    }

    public String getBlockedReason(String userId) {
        BlockedUser bu = blockedUsers.get(userId);
        return bu != null ? bu.reason() : null;
    }

    public BlockedUser getBlockedUser(String userId) {
        return blockedUsers.get(userId);
    }

    public List<BlockedUser> listBlockedUsers() {
        return List.copyOf(blockedUsers.values());
    }

    // ---- persistence ----

    private void restore() {
        for (String key : kv.keysWithPrefix(PREFIX_CASE)) {
            byte[] data = kv.get(key).orElse(null);
            if (data != null) {
                AuditCase ac = fromCaseJson(AxonJson.decode(data));
                if (ac != null) pendingCases.put(ac.hash(), ac);
            }
        }
        for (String key : kv.keysWithPrefix(PREFIX_HISTORY)) {
            byte[] data = kv.get(key).orElse(null);
            if (data != null) {
                AuditCase ac = fromCaseJson(AxonJson.decode(data));
                if (ac != null) history.put(ac.hash(), ac);
            }
        }
        for (String key : kv.keysWithPrefix(PREFIX_BLOCKED)) {
            byte[] data = kv.get(key).orElse(null);
            if (data != null) {
                BlockedUser bu = fromBlockedJson(AxonJson.decode(data));
                if (bu != null) blockedUsers.put(bu.userId(), bu);
            }
        }
    }

    // ---- serialization ----

    private static AxonValue toJson(AuditCase ac) {
        var events = new ArrayList<AxonValue>();
        for (AuditCaseEvent e : ac.events()) {
            events.add(AxonValue.object(java.util.Map.of(
                "type", AxonValue.str(e.type()),
                "user", AxonValue.str(e.userId()),
                "note", AxonValue.str(e.note()),
                "timestamp", AxonValue.num(e.timestamp()))));
        }
        var map = new java.util.LinkedHashMap<String, AxonValue>();
        map.put("hash", AxonValue.str(ac.hash()));
        map.put("user", AxonValue.str(ac.userId()));
        map.put("sql", AxonValue.str(ac.sql()));
        map.put("audit", AxonValue.str(ac.auditName()));
        map.put("classification", AxonValue.str(ac.classification()));
        map.put("status", AxonValue.str(ac.status()));
        map.put("created_at", AxonValue.num(ac.createdAt()));
        map.put("reason", AxonValue.str(ac.reason()));
        map.put("events", AxonValue.array(events));
        map.put("resolved_by", AxonValue.str(ac.resolvedBy()));
        map.put("resolution_note", AxonValue.str(ac.resolutionNote()));
        map.put("resolved_at", AxonValue.num(ac.resolvedAt()));
        return AxonValue.object(map);
    }

    private static AuditCase fromCaseJson(AxonValue v) {
        try {
            if (v == null || !v.isObject()) return null;
            var obj = v.asObject();
            AuditCase ac = new AuditCase(
                str(obj, "hash"), str(obj, "user"), str(obj, "sql"), str(obj, "audit"),
                str(obj, "classification"), num(obj, "created_at"));
            // restore reason
            String reason = str(obj, "reason");
            if (!reason.isEmpty()) ac.setReason(reason, ac.userId());
            // restore resolution
            String status = str(obj, "status");
            String resolvedBy = str(obj, "resolved_by");
            String resolutionNote = str(obj, "resolution_note");
            long resolvedAt = num(obj, "resolved_at");
            if (!"PENDING".equals(status) && !resolvedBy.isEmpty()) {
                ac.resolve(status, resolvedBy, resolutionNote);
                // override resolvedAt to the persisted value
            }
            return ac;
        } catch (Exception e) {
            return null;
        }
    }

    private static AxonValue toJson(BlockedUser bu) {
        return AxonValue.object(java.util.Map.of(
            "user", AxonValue.str(bu.userId()),
            "audit", AxonValue.str(bu.auditName()),
            "sql", AxonValue.str(bu.sql()),
            "reason", AxonValue.str(bu.reason()),
            "blocked_at", AxonValue.num(bu.blockedAt()),
            "blocked_by", AxonValue.str(bu.blockedBy())));
    }

    private static BlockedUser fromBlockedJson(AxonValue v) {
        try {
            if (v == null || !v.isObject()) return null;
            var obj = v.asObject();
            return new BlockedUser(
                str(obj, "user"), str(obj, "audit"), str(obj, "sql"), str(obj, "reason"),
                num(obj, "blocked_at"), str(obj, "blocked_by"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(java.util.Map<String, AxonValue> obj, String key) {
        AxonValue v = obj.get(key);
        return v != null && v.isString() ? v.asString() : "";
    }

    private static long num(java.util.Map<String, AxonValue> obj, String key) {
        AxonValue v = obj.get(key);
        return v != null && v.isNumber() ? v.asLong() : 0L;
    }
}