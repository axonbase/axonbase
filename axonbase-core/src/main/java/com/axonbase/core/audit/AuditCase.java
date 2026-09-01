package com.axonbase.core.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Caso de auditoria gerado quando um SQL classificado como WARNING ou DANGER.
 * Pendente ou resolvido. Mantém ledger completo de eventos.
 */
public final class AuditCase {

    private final String hash;
    private final String userId;
    private final String sql;
    private final String auditName;
    private final String classification;
    private String status;              // PENDING | AUTHORIZED | DENIED
    private final long createdAt;
    private String reason;
    private final List<AuditCaseEvent> events;
    private String resolvedBy;
    private String resolutionNote;
    private long resolvedAt;

    public AuditCase(String hash, String userId, String sql, String auditName,
                     String classification, long createdAt) {
        this.hash = hash;
        this.userId = userId;
        this.sql = sql;
        this.auditName = auditName;
        this.classification = classification;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.reason = "";
        this.events = new ArrayList<>();
        this.events.add(new AuditCaseEvent("CREATED", userId, "SQL: " + sql, createdAt));
        this.resolvedBy = "";
        this.resolutionNote = "";
        this.resolvedAt = 0;
    }

    public void addEvent(String type, String userId, String note) {
        events.add(new AuditCaseEvent(type, userId, note, System.currentTimeMillis()));
    }

    public void setReason(String reason, String userId) {
        this.reason = reason;
        addEvent("REASON_SET", userId, reason);
    }

    public void resolve(String newStatus, String by, String note) {
        this.status = newStatus;
        this.resolvedBy = by;
        this.resolutionNote = note;
        this.resolvedAt = System.currentTimeMillis();
        addEvent(newStatus.equals("AUTHORIZED") ? "AUTHORIZED" : "DENIED", by, note);
    }

    public String hash() { return hash; }
    public String userId() { return userId; }
    public String sql() { return sql; }
    public String auditName() { return auditName; }
    public String classification() { return classification; }
    public String status() { return status; }
    public long createdAt() { return createdAt; }
    public String reason() { return reason; }
    public List<AuditCaseEvent> events() { return List.copyOf(events); }
    public String resolvedBy() { return resolvedBy; }
    public String resolutionNote() { return resolutionNote; }
    public long resolvedAt() { return resolvedAt; }
}