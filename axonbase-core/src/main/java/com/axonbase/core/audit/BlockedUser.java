package com.axonbase.core.audit;

/**
 * Usuário bloqueado por AI Audit (classificação DANGER).
 */
public record BlockedUser(String userId, String auditName, String sql,
                          String reason, long blockedAt, String blockedBy) {
}