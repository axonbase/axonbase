package com.axonbase.core.audit;

import java.util.List;

/**
 * Evento individual no ledger de um AuditCase.
 */
public record AuditCaseEvent(String type, String userId, String note, long timestamp) {
}