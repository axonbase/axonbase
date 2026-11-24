package com.axonbase.core.pl;

import com.axonbase.core.catalog.Database;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.Authz;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sessões de depuração de um banco de dados: criação com limites, consulta e remoção. Cada sessão roda numa
 * thread própria e, por padrão, numa transação sempre revertida.
 */
public final class DebugManager {

    /** Máximo de sessões ativas ao mesmo tempo, por servidor. */
    public static final int DEFAULT_MAX_SESSIONS = 4;
    /** Ociosidade máxima padrão de uma sessão pausada, em segundos. */
    public static final long DEFAULT_IDLE_SECONDS = 300;
    private static final long RETENTION_MS = 10 * 60_000L;

    private final Datastore ds;
    private final ProgramService service;
    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final LongAdder timeouts = new LongAdder();
    private volatile int maxSessions = DEFAULT_MAX_SESSIONS;
    private volatile long idleSeconds = DEFAULT_IDLE_SECONDS;

    DebugManager(Datastore ds, ProgramService service) {
        this.ds = ds;
        this.service = service;
    }

    /** Máximo de sessões ativas simultâneas. */
    public void maxSessions(int n) {
        this.maxSessions = Math.max(1, n);
    }

    /** Segundos de ociosidade permitidos numa pausa antes de cancelar e reverter (0 = sem limite). */
    public void idleSeconds(long s) {
        this.idleSeconds = Math.max(0, s);
    }

    /** Sessões ativas agora (métrica {@code axe_pl_debug_sessions}). */
    public long active() {
        return sessions.values().stream().filter(DebugSession::active).count();
    }

    /** Sessões canceladas por ociosidade desde o início (métrica {@code axe_pl_debug_timeouts_total}). */
    public long timeouts() {
        return timeouts.sum();
    }

    void encerrada(DebugSession s, boolean timeout) {
        if (timeout) {
            timeouts.increment();
        }
    }

    /** Inicia uma sessão e a deixa executando (ou pausada, conforme breakpoints e stopOnEntry). */
    public DebugSession start(DebugRequest req) {
        limparAntigas();
        if (req.auth() != null && Authz.authenticated(req.auth())) {
            if (Authz.role(req.auth()) == Authz.Role.VIEWER) {
                throw new DebugException(DebugException.Kind.FORBIDDEN, "depurar exige papel operator ou superior");
            }
            if (req.commit() && !Authz.atLeast(req.auth(), Authz.Role.ADMIN)) {
                throw new DebugException(DebugException.Kind.FORBIDDEN, "commit=true exige papel admin");
            }
        }
        Database database = ds.findDatabase(req.ns(), req.db());
        if (database == null) {
            throw new DebugException(DebugException.Kind.NOT_FOUND, "banco inexistente");
        }
        String name = req.program() == null ? "" : req.program().startsWith("fn::")
            ? req.program().substring(4) : req.program();
        int sep = name.indexOf("::");
        String owner = sep > 0 ? name.substring(0, sep) : name;
        String fn = sep > 0 ? name.substring(sep + 2) : name;
        ProgramDef def = database.programs().get(owner);
        if (def == null) {
            throw new DebugException(DebugException.Kind.NOT_FOUND, "programa '" + owner + "' nao existe");
        }
        if ((sep > 0) != (def.kind() == ProgramDef.Kind.PACKAGE)) {
            throw new DebugException(DebugException.Kind.BAD_REQUEST, def.kind() == ProgramDef.Kind.PACKAGE
                ? "'" + owner + "' e um package: informe pacote::funcao" : "'" + owner + "' nao e um package");
        }
        if (def.broken()) {
            throw new DebugException(DebugException.Kind.CONFLICT, "'" + owner + "' esta quebrado: " + def.error());
        }
        for (Integer b : req.breakpoints()) {
            if (b == null || b < 1) {
                throw new DebugException(DebugException.Kind.BAD_REQUEST, "linha de breakpoint invalida: " + b);
            }
        }
        DebugSession session;
        synchronized (this) {
            if (active() >= maxSessions) {
                throw new DebugException(DebugException.Kind.LIMIT,
                    "limite de " + maxSessions + " sessoes de depuracao simultaneas atingido");
            }
            String id = "dbg-" + Long.toHexString(System.nanoTime()) + "-" + counter.incrementAndGet();
            session = new DebugSession(id, this, ds, service, req, def, fn, idleSeconds * 1_000_000_000L);
            sessions.put(id, session);
        }
        session.start(req.args());
        return session;
    }

    /** Sessão pelo id, ou {@code null}. */
    public DebugSession get(String id) {
        return sessions.get(id);
    }

    /** Todas as sessões conhecidas (ativas e recém terminadas). */
    public List<DebugSession> list() {
        return new ArrayList<>(sessions.values());
    }

    /** Encerra (se ativa) e esquece a sessão. Devolve {@code false} se o id não existe. */
    public boolean remove(String id) {
        DebugSession s = sessions.remove(id);
        if (s == null) {
            return false;
        }
        s.stop();
        return true;
    }

    private void limparAntigas() {
        long limite = System.currentTimeMillis() - RETENTION_MS;
        sessions.values().removeIf(s -> !s.active() && s.endedAt() > 0 && s.endedAt() < limite);
    }
}
