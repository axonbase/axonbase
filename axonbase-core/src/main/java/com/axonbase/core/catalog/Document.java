package com.axonbase.core.catalog;

import com.axonbase.value.AxonValue;

/**
 * Registro almacenado (unha fila de táboa). Mantén os dous estados
 * copy-on-write: {@code initial} (o valor lido da base) e {@code current}
 * (o valor coas modificacións aplicadas). Toda fase posterior queda portada
 * por {@code isModified()}, igual que no SurrealDB.
 */
public final class Document {

    private final RecordId id;
    private final AxonValue initial;
    private AxonValue current;
    private double searchScore;
    private java.util.List<String> searchTerms = java.util.List.of();

    public Document(RecordId id, AxonValue initial) {
        this.id = id;
        this.initial = initial;
        this.current = initial;
    }

    public RecordId id() {
        return id;
    }

    public AxonValue initial() {
        return initial;
    }

    public AxonValue current() {
        return current;
    }

    public void setCurrent(AxonValue value) {
        this.current = value;
    }

    public boolean isModified() {
        return !initial.equals(current);
    }

    /** Relevância calculada para a consulta full-text atual. */
    public double searchScore() {
        return searchScore;
    }

    public void searchScore(double score) {
        this.searchScore = score;
    }

    public java.util.List<String> searchTerms() {
        return searchTerms;
    }

    public void searchTerms(java.util.List<String> terms) {
        this.searchTerms = java.util.List.copyOf(terms);
    }

    @Override
    public String toString() {
        return id + " = " + current;
    }
}
