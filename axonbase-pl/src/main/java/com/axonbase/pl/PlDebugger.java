package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Depurador interativo. E um {@link PlDebugHook}: a thread que executa o programa bloqueia dentro de
 * {@code onLine} quando atinge um breakpoint ou termina um passo, e outra thread (o controlador) a retoma com
 * {@link #comando(Comando)}. Enquanto pausada, a propria thread de execucao atende {@link #avaliar(String)}, de
 * modo que a expressao roda com as mesmas variaveis de contexto da thread (sessao, transacao).
 *
 * <p>O tempo parado nao conta contra o prazo do programa (vira credito em {@link PlLimits}). Se ninguem der um
 * comando dentro do limite de ociosidade, a execucao e cancelada.
 */
public final class PlDebugger implements PlDebugHook {

    /** Estado da execucao depurada. */
    public enum Estado { EXECUTANDO, PAUSADO, FINALIZADO }

    /** Como retomar. */
    public enum Comando { CONTINUAR, PASSO_SOBRE, PASSO_DENTRO, PASSO_FORA }

    /** Variavel renderizada. */
    public record Variavel(String nome, String tipo, String valor) {
    }

    /** Quadro da pilha de chamadas, com a linha do corpo do usuario. */
    public record Quadro(String funcao, String programa, int linha, List<Variavel> locais) {
    }

    /** Fotografia de uma pausa, tirada na thread de execucao. */
    public record Pausa(long numero, String motivo, List<Quadro> pilha, List<Variavel> globais) {
    }

    private record Avaliacao(String expressao, CompletableFuture<AxonValue> resultado) {
    }

    private record Retomada(Comando comando) {
    }

    private enum Parada { INSTANCIA }

    private static final int MAX_VARIAVEIS = 100;

    private final String programaAlvo;
    private final int deslocamento;
    private final PlLimits limits;
    private final PlHost hostAvaliacao;
    private final long ociosidadeNanos;
    private final Set<Integer> breakpoints = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingQueue<Object> fila = new LinkedBlockingQueue<>();
    private final Object trava = new Object();

    private volatile Estado estado = Estado.EXECUTANDO;
    private volatile boolean pausaPedida;
    private volatile boolean paradaPedida;
    private volatile boolean expirou;
    private volatile Pausa ultimaPausa;
    private volatile PlFrame quadroPausado;
    private volatile Consumer<Pausa> aoPausar = p -> { };
    private volatile Runnable aoRetomar = () -> { };
    private volatile PlProfiler perfil;
    private long contadorPausas;

    // so a thread de execucao le e escreve
    private Comando modo = Comando.CONTINUAR;
    private int profundidadeBase;

    /**
     * Cria o depurador.
     *
     * @param programaAlvo nome do programa cujo corpo recebe breakpoints (os outros so param em passo para dentro)
     * @param deslocamento linhas a subtrair da linha interna para obter a linha do usuario (a funcao e a
     *     procedure ganham uma linha de cabecalho)
     * @param limits limites da execucao (recebem o credito do tempo parado e o cancelamento)
     * @param hostAvaliacao ponte somente leitura usada por {@link #avaliar(String)}
     * @param ociosidadeNanos tempo maximo parado sem comando antes de cancelar (0 = sem limite)
     */
    public PlDebugger(String programaAlvo, int deslocamento, PlLimits limits, PlHost hostAvaliacao,
                      long ociosidadeNanos) {
        this.programaAlvo = programaAlvo;
        this.deslocamento = deslocamento;
        this.limits = limits;
        this.hostAvaliacao = hostAvaliacao;
        this.ociosidadeNanos = ociosidadeNanos;
    }

    // ------------------------------------------------------------------
    // configuracao
    // ------------------------------------------------------------------

    /** Substitui os breakpoints (linhas do corpo do usuario). */
    public void breakpoints(Set<Integer> linhas) {
        breakpoints.clear();
        breakpoints.addAll(linhas);
    }

    /** Breakpoints atuais. */
    public Set<Integer> breakpoints() {
        return Set.copyOf(breakpoints);
    }

    /** Se verdadeiro, pausa no primeiro comando do corpo do usuario. */
    public void pararNaEntrada(boolean v) {
        this.modo = v ? Comando.PASSO_DENTRO : Comando.CONTINUAR;
    }

    /** Chamado na thread de execucao, ao pausar. */
    public void aoPausar(Consumer<Pausa> c) {
        this.aoPausar = c;
    }

    /** Chamado na thread de execucao, ao retomar. */
    public void aoRetomar(Runnable r) {
        this.aoRetomar = r;
    }

    /** Perfilador a ser compensado pelo tempo parado. */
    public void perfil(PlProfiler p) {
        this.perfil = p;
    }

    // ------------------------------------------------------------------
    // controle (outras threads)
    // ------------------------------------------------------------------

    public Estado estado() {
        return estado;
    }

    /** Ultima pausa registrada (null se nunca pausou). */
    public Pausa pausa() {
        return ultimaPausa;
    }

    /** Verdadeiro se a execucao foi cancelada por ociosidade. */
    public boolean expirou() {
        return expirou;
    }

    /** Retoma a execucao pausada. */
    public void comando(Comando c) {
        synchronized (trava) {
            if (estado != Estado.PAUSADO) {
                throw new PlDebugError("a execucao nao esta pausada");
            }
            estado = Estado.EXECUTANDO;
        }
        fila.add(new Retomada(c));
    }

    /** Pede pausa no proximo comando executado. */
    public void pausar() {
        if (estado == Estado.EXECUTANDO) {
            pausaPedida = true;
        }
    }

    /** Cancela a execucao (acorda a thread se estiver pausada). */
    public void parar() {
        paradaPedida = true;
        limits.cancelar();
        fila.add(Parada.INSTANCIA);
    }

    /** Marcado pelo dono da thread de execucao quando ela termina. */
    public void finalizar() {
        synchronized (trava) {
            estado = Estado.FINALIZADO;
        }
        quadroPausado = null;
        esvaziar();
    }

    /** Avalia uma expressao no quadro de topo da pausa. Bloqueia ate a thread de execucao responder. */
    public AxonValue avaliar(String expressao) {
        if (estado != Estado.PAUSADO) {
            throw new PlDebugError("a execucao nao esta pausada");
        }
        CompletableFuture<AxonValue> f = new CompletableFuture<>();
        fila.add(new Avaliacao(expressao, f));
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new PlDebugError(String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new PlDebugError("a avaliacao demorou demais");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlDebugError("avaliacao interrompida");
        }
    }

    // ------------------------------------------------------------------
    // gancho (thread de execucao)
    // ------------------------------------------------------------------

    @Override
    public void onLine(PlFrame frame, int line) {
        if (paradaPedida) {
            throw cancelamento();
        }
        boolean alvo = frame.programa().equals(programaAlvo);
        int linhaUsuario = alvo ? line - deslocamento : line;
        if (linhaUsuario < 1) {
            return;
        }
        String motivo = null;
        if (alvo && breakpoints.contains(linhaUsuario)) {
            motivo = "breakpoint";
        } else if (pausaPedida) {
            pausaPedida = false;
            motivo = "pause";
        } else {
            switch (modo) {
                case PASSO_DENTRO -> motivo = "step";
                case PASSO_SOBRE -> motivo = frame.profundidade() <= profundidadeBase ? "step" : null;
                case PASSO_FORA -> motivo = frame.profundidade() < profundidadeBase ? "step" : null;
                default -> { }
            }
        }
        if (motivo != null) {
            pausarAqui(frame, motivo);
        }
    }

    private PlLimitError cancelamento() {
        return new PlLimitError(PlLimitError.Tipo.CANCELADO, expirou
            ? "sessao de depuracao encerrada por ociosidade" : "depuracao encerrada");
    }

    private void pausarAqui(PlFrame frame, String motivo) {
        Pausa p = new Pausa(++contadorPausas, motivo, pilha(frame), variaveis(frame.globais()));
        long inicio = System.nanoTime();
        quadroPausado = frame;
        ultimaPausa = p;
        synchronized (trava) {
            estado = Estado.PAUSADO;
        }
        try {
            aoPausar.accept(p);
            Comando escolhido = esperar(frame);
            if (escolhido == null) {
                throw cancelamento();
            }
            modo = escolhido;
            profundidadeBase = frame.profundidade();
        } finally {
            long parado = System.nanoTime() - inicio;
            limits.creditarNanos(parado);
            PlProfiler pr = perfil;
            if (pr != null) {
                pr.descontar(parado);
            }
            quadroPausado = null;
            synchronized (trava) {
                if (estado == Estado.PAUSADO) {
                    estado = Estado.EXECUTANDO;
                }
            }
            esvaziar();
        }
        aoRetomar.run();
    }

    /** Atende avaliacoes ate chegar um comando. Devolve null se for parada, ociosidade ou interrupcao. */
    private Comando esperar(PlFrame frame) {
        while (true) {
            Object item;
            try {
                item = ociosidadeNanos > 0 ? fila.poll(ociosidadeNanos, TimeUnit.NANOSECONDS) : fila.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (item == null) {
                expirou = true;
                paradaPedida = true;
                return null;
            }
            if (item instanceof Retomada r) {
                return paradaPedida ? null : r.comando();
            }
            if (item instanceof Avaliacao a) {
                try {
                    a.resultado().complete(PlDebugEval.avaliar(frame, a.expressao(), hostAvaliacao, null));
                } catch (RuntimeException e) {
                    a.resultado().completeExceptionally(e);
                }
                continue;
            }
            return null;
        }
    }

    private void esvaziar() {
        Object item;
        while ((item = fila.poll()) != null) {
            if (item instanceof Avaliacao a) {
                a.resultado().completeExceptionally(new PlDebugError("a execucao nao esta mais pausada"));
            }
        }
    }

    private List<Quadro> pilha(PlFrame topo) {
        List<Quadro> out = new ArrayList<>();
        for (PlFrame q = topo; q != null; q = q.chamador()) {
            boolean alvo = q.programa().equals(programaAlvo);
            int linha = alvo ? q.linha() - deslocamento : q.linha();
            boolean artificial = q.chamador() == null && q.funcao().equals("<modulo>") && linha <= 1;
            if ((linha < 1 || artificial) && q != topo) {
                continue;
            }
            out.add(new Quadro(q.funcao(), q.programa(), linha, variaveis(q.locais())));
        }
        return out;
    }

    private static List<Variavel> variaveis(Map<String, AxonValue> m) {
        List<Variavel> out = new ArrayList<>();
        for (Map.Entry<String, AxonValue> e : m.entrySet()) {
            if (out.size() >= MAX_VARIAVEIS) {
                break;
            }
            out.add(new Variavel(e.getKey(), PlRender.tipo(e.getValue()), PlRender.valor(e.getValue())));
        }
        return out;
    }
}
