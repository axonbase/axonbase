package com.axonbase.core.pl;

import com.axonbase.pl.PlNamespace;
import com.axonbase.pl.PlProgram;
import com.axonbase.value.AxonValue;
import java.util.List;

/**
 * Função, procedure ou package definido pelo usuário. Imutável, exceto pelo cache do espaço de nomes carregado
 * (as variáveis globais do módulo, compartilhadas entre chamadas e descartadas quando o objeto é substituído).
 */
public final class ProgramDef {

    /** Tipo do objeto. */
    public enum Kind { FUNCTION, PROCEDURE, PACKAGE }

    /** Parâmetro com tipo (opcional) e padrão já avaliado ({@code defaultValue} nulo quando não há). */
    public record Param(String name, String type, boolean hasDefault, AxonValue defaultValue, String defaultText) {
    }

    private final Kind kind;
    private final String name;
    private final List<Param> params;
    private final String returns;
    private final boolean definer;
    private final AxonValue owner;
    private final String body;
    private final String definition;
    private final long version;
    private final long created;
    private final long updated;
    private final String error;
    private final PlProgram program;
    private volatile PlNamespace loaded;
    private final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();

    ProgramDef(Kind kind, String name, List<Param> params, String returns, boolean definer, AxonValue owner,
               String body, String definition, long version, long created, long updated, String error,
               PlProgram program) {
        this.kind = kind;
        this.name = name;
        this.params = List.copyOf(params);
        this.returns = returns;
        this.definer = definer;
        this.owner = owner;
        this.body = body;
        this.definition = definition;
        this.version = version;
        this.created = created;
        this.updated = updated;
        this.error = error;
        this.program = program;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public List<Param> params() {
        return params;
    }

    public String returns() {
        return returns;
    }

    /** SECURITY DEFINER: roda com os direitos do dono. */
    public boolean definer() {
        return definer;
    }

    /** Claims de quem criou o objeto (nulo no modo embutido sem autenticação). */
    public AxonValue owner() {
        return owner;
    }

    /** Corpo normalizado (o que o usuário escreveu entre os delimitadores). */
    public String body() {
        return body;
    }

    /** Texto AxonQL que recria o objeto (CREATE OR REPLACE ... AS $$ ... $$). */
    public String definition() {
        return definition;
    }

    public long version() {
        return version;
    }

    public long created() {
        return created;
    }

    public long updated() {
        return updated;
    }

    /** Mensagem de erro de compilação quando o objeto está quebrado, ou nulo. */
    public String error() {
        return error;
    }

    public boolean broken() {
        return error != null;
    }

    /** Programa compilado (nulo quando quebrado). */
    PlProgram program() {
        return program;
    }

    /** Trava do estado global do package: tomada só em chamadas a packages com estado mutável. */
    java.util.concurrent.locks.ReentrantLock stateLock() {
        return stateLock;
    }

    PlNamespace loaded() {
        return loaded;
    }

    void loaded(PlNamespace ns) {
        this.loaded = ns;
    }
}
