package com.axonbase.core.pl;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.pl.PlDebugHook;
import com.axonbase.pl.PlDebugger;
import com.axonbase.pl.PlError;
import com.axonbase.pl.PlFrame;
import com.axonbase.pl.PlHooks;
import com.axonbase.pl.PlLimits;
import com.axonbase.pl.PlProfiler;
import com.axonbase.pl.PlRender;
import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Uma sessão de depuração: executa um programa numa thread própria, dentro de uma transação que por padrão é
 * sempre revertida, e expõe estado, eventos e comandos. Seguro para uso por várias threads de requisição.
 */
public final class DebugSession {

    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String FINISHED = "finished";
    public static final String ERROR = "error";
    public static final String STOPPED = "stopped";
    public static final String TIMEOUT = "timeout";

    private static final int MAX_EVENTS = 2000;
    private static final long MAX_WAIT_MS = 30_000;

    private final String id;
    private final DebugManager manager;
    private final Datastore ds;
    private final ProgramService service;
    private final DebugRequest request;
    private final ProgramDef def;
    private final String function;
    private final int offset;
    private final String target;
    private final Session session;
    private final PlLimits limits = PlLimits.padrao();
    private final PlDebugger debugger;
    private final PlProfiler profiler;
    private final QueryProfile queries = new QueryProfile();
    private final List<AxonValue> events = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    private final Object lock = new Object();
    private long seq;
    private String state = RUNNING;
    private AxonValue pause;
    private AxonValue result;
    private String error;
    private boolean committed;
    private List<String> log = List.of();
    private AxonValue profile;
    private volatile long endedAt;
    private volatile boolean stopRequested;
    private Thread thread;

    DebugSession(String id, DebugManager manager, Datastore ds, ProgramService service, DebugRequest request,
                 ProgramDef def, String function, long idleNanos) {
        this.id = id;
        this.manager = manager;
        this.ds = ds;
        this.service = service;
        this.request = request;
        this.def = def;
        this.function = function;
        this.offset = def.kind() == ProgramDef.Kind.PACKAGE ? 0 : 1;
        this.target = def.program().nome();
        this.session = new Session(request.ns(), request.db());
        if (request.auth() != null) {
            this.session.auth(request.auth());
        }
        this.profiler = new PlProfiler();
        this.debugger = new PlDebugger(target, offset, limits, evalHost(), idleNanos);
        this.debugger.breakpoints(request.breakpoints());
        this.debugger.pararNaEntrada(request.stopOnEntry());
        this.debugger.perfil(profiler);
        this.debugger.aoPausar(this::aoPausar);
        this.debugger.aoRetomar(() -> emitir("resumed", Map.of()));
    }

    /** Ponte somente leitura usada para avaliar expressões (mesma sessão, sem escrita e sem DDL). */
    private SessionHost evalHost() {
        SessionHost.CallState st = new SessionHost.CallState(false, def);
        st.noDdl = true;
        return new SessionHost(service, ds, session, service.catalog(request.ns(), request.db()), st);
    }

    void start(List<AxonValue> args) {
        PlDebugHook linhas = new PlDebugHook() {
            @Override
            public void onHostCall(PlFrame frame, String modulo, String nome, List<AxonValue> a,
                                   Map<String, AxonValue> kw) {
                boolean alvo = frame.programa().equals(target);
                queries.currentLine(alvo ? frame.linha() - offset : frame.linha());
            }
        };
        PlDebugHook hook = PlHooks.compor(debugger, profiler, linhas);
        DebugRun run = new DebugRun(hook, queries, limits, !request.commit());
        boolean writable = def.kind() != ProgramDef.Kind.FUNCTION;
        thread = new Thread(() -> executar(run, args, writable), "axon-pl-debug-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    private void executar(DebugRun run, List<AxonValue> args, boolean writable) {
        String finalState;
        String failure = null;
        try {
            ds.beginSession(session);
            AxonValue r = service.invokeDebug(session, service.catalog(request.ns(), request.db()), def, function,
                args, writable, run);
            boolean commit = request.commit();
            if (commit) {
                ds.commitSession(session);
            } else {
                ds.cancelSession(session);
            }
            synchronized (lock) {
                result = r;
                committed = commit;
            }
            finalState = FINISHED;
        } catch (AxonError | PlError e) {
            failure = e.getMessage();
            finalState = classify();
        } catch (RuntimeException e) {
            failure = "erro interno da depuracao: " + e;
            finalState = classify();
        } finally {
            rollbackIfOpen();
        }
        encerrar(finalState, failure);
    }

    private String classify() {
        if (debugger.expirou()) {
            return TIMEOUT;
        }
        return stopRequested ? STOPPED : ERROR;
    }

    private void rollbackIfOpen() {
        try {
            if (session.inTransaction()) {
                ds.cancelSession(session);
            }
        } catch (RuntimeException e) {
            System.err.println("{\"event\":\"pl_debug_rollback_failed\",\"session\":\"" + id + "\"}");
        }
    }

    private void encerrar(String finalState, String failure) {
        debugger.finalizar();
        AxonValue prof = profileValue();
        List<String> lines = service.lastCallLog();
        synchronized (lock) {
            this.state = finalState;
            this.error = failure;
            this.log = lines;
            this.profile = prof;
            this.pause = null;
            this.endedAt = System.currentTimeMillis();
        }
        manager.encerrada(this, finalState.equals(TIMEOUT));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", finalState);
        if (failure != null) {
            d.put("error", failure);
        }
        d.put("committed", committed);
        emitir(finalState.equals(FINISHED) ? "finished" : finalState, d);
    }

    // ------------------------------------------------------------------
    // consulta
    // ------------------------------------------------------------------

    public String id() {
        return id;
    }

    /** Namespace do banco depurado. */
    public String ns() {
        return request.ns();
    }

    /** Banco depurado. */
    public String db() {
        return request.db();
    }

    /** Verdadeiro até a execução terminar (pausada ou não). */
    public boolean active() {
        synchronized (lock) {
            return state.equals(RUNNING) || state.equals(PAUSED);
        }
    }

    /** Instante (ms desde a época) em que a execução terminou, ou 0. */
    public long endedAt() {
        return endedAt;
    }

    public String state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Estado completo, para {@code GET} da sessão. */
    public AxonValue describe() {
        synchronized (lock) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("id", AxonValue.str(id));
            m.put("state", AxonValue.str(state));
            m.put("program", AxonValue.str(request.program()));
            m.put("kind", AxonValue.str(def.kind().name().toLowerCase(java.util.Locale.ROOT)));
            m.put("function", AxonValue.str(function));
            m.put("commit", AxonValue.bool(request.commit()));
            m.put("source", AxonValue.str(def.body()));
            m.put("startedAt", AxonValue.num(startedAt));
            List<AxonValue> bps = new ArrayList<>();
            for (Integer b : new TreeSet<>(debugger.breakpoints())) {
                bps.add(AxonValue.num((long) b));
            }
            m.put("breakpoints", AxonValue.array(bps));
            m.put("pause", pause == null ? AxonValue.nul() : pause);
            m.put("lastEventSeq", AxonValue.num(seq));
            if (!active()) {
                m.put("result", result == null ? AxonValue.nul() : result);
                m.put("error", error == null ? AxonValue.nul() : AxonValue.str(error));
                m.put("committed", AxonValue.bool(committed));
                List<AxonValue> l = new ArrayList<>();
                log.forEach(x -> l.add(AxonValue.str(x)));
                m.put("log", AxonValue.array(l));
                if (profile != null) {
                    m.put("profile", profile);
                }
            }
            return AxonValue.object(m);
        }
    }

    /**
     * Eventos com número maior que {@code after}. Espera até {@code waitMs} (long poll) se ainda não houver
     * nenhum e a sessão estiver ativa.
     */
    public AxonValue events(long after, long waitMs) {
        long deadline = System.nanoTime() + Math.min(Math.max(waitMs, 0), MAX_WAIT_MS) * 1_000_000L;
        synchronized (lock) {
            while (seq <= after && active()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            List<AxonValue> out = new ArrayList<>();
            for (AxonValue e : events) {
                if (e.asObject().get("seq").asLong() > after) {
                    out.add(e);
                }
            }
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("events", AxonValue.array(out));
            m.put("next", AxonValue.num(seq));
            m.put("state", AxonValue.str(state));
            return AxonValue.object(m);
        }
    }

    // ------------------------------------------------------------------
    // comandos
    // ------------------------------------------------------------------

    /** continue, stepOver, stepInto, stepOut, pause ou stop. */
    public AxonValue command(String name) {
        if (name == null) {
            throw new DebugException(DebugException.Kind.BAD_REQUEST, "comando ausente");
        }
        switch (name) {
            case "continue" -> resume(PlDebugger.Comando.CONTINUAR);
            case "stepOver" -> resume(PlDebugger.Comando.PASSO_SOBRE);
            case "stepInto" -> resume(PlDebugger.Comando.PASSO_DENTRO);
            case "stepOut" -> resume(PlDebugger.Comando.PASSO_FORA);
            case "pause" -> {
                if (!active()) {
                    throw new DebugException(DebugException.Kind.CONFLICT, "a sessao ja terminou");
                }
                debugger.pausar();
            }
            case "stop" -> stop();
            default -> throw new DebugException(DebugException.Kind.BAD_REQUEST, "comando desconhecido: " + name
                + " (use continue, stepOver, stepInto, stepOut, pause ou stop)");
        }
        return describe();
    }

    private void resume(PlDebugger.Comando c) {
        synchronized (lock) {
            if (!state.equals(PAUSED)) {
                throw new DebugException(DebugException.Kind.CONFLICT, "a execucao nao esta pausada (estado: "
                    + state + ")");
            }
            state = RUNNING;
            pause = null;
        }
        try {
            debugger.comando(c);
        } catch (RuntimeException e) {
            throw new DebugException(DebugException.Kind.CONFLICT, e.getMessage());
        }
    }

    /** Cancela a execução e reverte a transação. Espera a thread terminar (até 10 s). */
    public void stop() {
        if (!active()) {
            return;
        }
        stopRequested = true;
        debugger.parar();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Substitui os breakpoints (linhas do corpo do usuário). */
    public void breakpoints(Set<Integer> lines) {
        for (Integer l : lines) {
            if (l == null || l < 1) {
                throw new DebugException(DebugException.Kind.BAD_REQUEST, "linha de breakpoint invalida: " + l);
            }
        }
        debugger.breakpoints(lines);
    }

    /** Avalia uma expressão somente leitura no quadro de topo da pausa. */
    public AxonValue evaluate(String expression) {
        try {
            AxonValue v = debugger.avaliar(expression);
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("type", AxonValue.str(PlRender.tipo(v)));
            m.put("value", AxonValue.str(PlRender.valor(v, 2000, 5)));
            return AxonValue.object(m);
        } catch (com.axonbase.pl.PlDebugError e) {
            throw new DebugException(e.mensagem().contains("pausada") ? DebugException.Kind.CONFLICT
                : DebugException.Kind.BAD_REQUEST, e.mensagem());
        } catch (PlError e) {
            throw new DebugException(DebugException.Kind.BAD_REQUEST, e.tipo() + ": " + e.mensagem());
        }
    }

    // ------------------------------------------------------------------
    // eventos e valores
    // ------------------------------------------------------------------

    private void aoPausar(PlDebugger.Pausa p) {
        AxonValue v = pauseValue(p);
        synchronized (lock) {
            state = PAUSED;
            pause = v;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("pause", v);
        emitir("paused", d);
    }

    private void emitir(String type, Map<String, ?> data) {
        synchronized (lock) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("seq", AxonValue.num(++seq));
            m.put("type", AxonValue.str(type));
            m.put("at", AxonValue.num(System.currentTimeMillis()));
            for (Map.Entry<String, ?> e : data.entrySet()) {
                Object o = e.getValue();
                m.put(e.getKey(), o instanceof AxonValue av ? av : o instanceof Boolean b ? AxonValue.bool(b)
                    : AxonValue.str(String.valueOf(o)));
            }
            events.add(AxonValue.object(m));
            if (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
            lock.notifyAll();
        }
    }

    private static AxonValue pauseValue(PlDebugger.Pausa p) {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        m.put("number", AxonValue.num(p.numero()));
        m.put("reason", AxonValue.str(p.motivo()));
        List<AxonValue> stack = new ArrayList<>();
        for (PlDebugger.Quadro q : p.pilha()) {
            Map<String, AxonValue> f = new LinkedHashMap<>();
            f.put("function", AxonValue.str(q.funcao()));
            f.put("program", AxonValue.str(q.programa()));
            f.put("line", AxonValue.num((long) q.linha()));
            f.put("locals", vars(q.locais()));
            stack.add(AxonValue.object(f));
        }
        m.put("stack", AxonValue.array(stack));
        m.put("globals", vars(p.globais()));
        return AxonValue.object(m);
    }

    private static AxonValue vars(List<PlDebugger.Variavel> l) {
        List<AxonValue> out = new ArrayList<>();
        for (PlDebugger.Variavel v : l) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("name", AxonValue.str(v.nome()));
            m.put("type", AxonValue.str(v.tipo()));
            m.put("value", AxonValue.str(v.valor()));
            out.add(AxonValue.object(m));
        }
        return AxonValue.array(out);
    }

    /** Rastro: contagem e tempo por linha do corpo do usuário e consultas db.* disparadas. */
    private AxonValue profileValue() {
        List<PlProfiler.Linha> lines = new ArrayList<>(profiler.linhas());
        lines.sort(Comparator.comparingInt(PlProfiler.Linha::linha));
        List<AxonValue> ls = new ArrayList<>();
        for (PlProfiler.Linha l : lines) {
            boolean alvo = l.programa().equals(target);
            int line = alvo ? l.linha() - offset : l.linha();
            if (line < 1) {
                continue;
            }
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("program", AxonValue.str(l.programa()));
            m.put("line", AxonValue.num((long) line));
            m.put("count", AxonValue.num(l.execucoes()));
            m.put("timeUs", AxonValue.num(l.nanos() / 1000));
            ls.add(AxonValue.object(m));
        }
        List<AxonValue> qs = new ArrayList<>();
        for (QueryProfile.Query q : queries.queries()) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("sql", AxonValue.str(q.sql()));
            m.put("durationUs", AxonValue.num(q.nanos() / 1000));
            m.put("rows", AxonValue.num(q.rows()));
            m.put("line", AxonValue.num((long) q.line()));
            qs.add(AxonValue.object(m));
        }
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("lines", AxonValue.array(ls));
        out.put("queries", AxonValue.array(qs));
        return AxonValue.object(out);
    }
}
