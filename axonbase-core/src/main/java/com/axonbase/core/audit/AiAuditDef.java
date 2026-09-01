package com.axonbase.core.audit;

import java.util.List;

/**
 * Definição de um AI AUDIT: nome, prompts originais e regras geradas pela IA.
 */
public final class AiAuditDef {

    private final String name;
    private final String warningPrompt;
    private final String dangerPrompt;
    private final List<AiAuditRule> warningRules;
    private final List<AiAuditRule> dangerRules;
    private final long createdAt;
    private final String createdBy;

    public AiAuditDef(String name, String warningPrompt, String dangerPrompt,
                      List<AiAuditRule> warningRules, List<AiAuditRule> dangerRules,
                      long createdAt, String createdBy) {
        this.name = name;
        this.warningPrompt = warningPrompt;
        this.dangerPrompt = dangerPrompt;
        this.warningRules = List.copyOf(warningRules);
        this.dangerRules = List.copyOf(dangerRules);
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public String name() { return name; }
    public String warningPrompt() { return warningPrompt; }
    public String dangerPrompt() { return dangerPrompt; }
    public List<AiAuditRule> warningRules() { return warningRules; }
    public List<AiAuditRule> dangerRules() { return dangerRules; }
    public long createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }

    /** Classifica um SQL: retorna "SAFE", "WARNING" ou "DANGER". */
    public String classify(String sql) {
        for (AiAuditRule rule : dangerRules) {
            if (rule.matches(sql)) return "DANGER";
        }
        for (AiAuditRule rule : warningRules) {
            if (rule.matches(sql)) return "WARNING";
        }
        return "SAFE";
    }
}