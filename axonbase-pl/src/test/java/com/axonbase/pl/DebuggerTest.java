package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Depurador interativo: breakpoints, passos, pilha, variaveis, avaliacao e pausa entre threads. */
class DebuggerTest {

    static final String SRC = String.join("\n",
            "def f(x):",
            "    y = x + 1",
            "    return y",
            "def main():",
            "    a = f(1)",
            "    b = a * 2",
            "    return b",
            "");

    static final String LOOP = String.join("\n",
            "def main():",
            "    t = 0",
            "    for i in range(3):",
            "        t = t + i",
            "    return t",
            "");

    private Thread thread;
    private final AtomicReference<Object> resultado = new AtomicReference<>();
    private final BlockingQueue<PlDebugger.Pausa> pausas = new LinkedBlockingQueue<>();

    @AfterEach
    void limpar() throws Exception {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            thread.join(2000);
        }
    }

    private PlDebugger iniciar(String src, PlLimits lim, long ociosidadeMs, Set<Integer> bps, boolean entrada) {
        PlProgram p = PlCompiler.compile(src, "teste.py");
        PlDebugger d = new PlDebugger("teste.py", 0, lim, new PlHostMemoria(), ociosidadeMs * 1_000_000L);
        d.breakpoints(bps);
        d.pararNaEntrada(entrada);
        d.aoPausar(pausas::add);
        thread = new Thread(() -> {
            try {
                resultado.set(new PlRuntime().call(p, "main", List.of(), null, new PlHostMemoria(), lim, d));
            } catch (Throwable e) {
                resultado.set(e);
            } finally {
                d.finalizar();
            }
        }, "teste-depurador");
        thread.start();
        return d;
    }

    private PlDebugger iniciar(String src, Set<Integer> bps) {
        return iniciar(src, Pl.limites(), 10_000, bps, false);
    }

    private PlDebugger.Pausa proxima() throws Exception {
        PlDebugger.Pausa p = pausas.poll(5, TimeUnit.SECONDS);
        assertNotNull(p, "era esperada uma pausa");
        return p;
    }

    private AxonValue fim() throws Exception {
        thread.join(5000);
        assertFalse(thread.isAlive());
        Object r = resultado.get();
        if (r instanceof Throwable t) {
            throw new AssertionError(t);
        }
        return (AxonValue) r;
    }

    private static String var(List<PlDebugger.Variavel> l, String nome) {
        return l.stream().filter(v -> v.nome().equals(nome)).findFirst().map(PlDebugger.Variavel::valor).orElse(null);
    }

    @Test
    void breakpointPausaNaLinhaECcontinuaAteOFim() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(6));
        PlDebugger.Pausa p = proxima();
        assertEquals("breakpoint", p.motivo());
        assertEquals("main", p.pilha().get(0).funcao());
        assertEquals(6, p.pilha().get(0).linha());
        assertEquals("2", var(p.pilha().get(0).locais(), "a"));
        assertEquals(PlDebugger.Estado.PAUSADO, d.estado());
        d.comando(PlDebugger.Comando.CONTINUAR);
        assertEquals(4, fim().asLong());
        assertEquals(PlDebugger.Estado.FINALIZADO, d.estado());
    }

    @Test
    void passoParaDentroMostraPilhaEVariaveisDaFuncaoChamada() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(5));
        proxima();
        d.comando(PlDebugger.Comando.PASSO_DENTRO);
        PlDebugger.Pausa p = proxima();
        assertEquals("step", p.motivo());
        assertEquals(2, p.pilha().get(0).linha());
        assertEquals("f", p.pilha().get(0).funcao());
        assertEquals("main", p.pilha().get(1).funcao());
        assertEquals(5, p.pilha().get(1).linha());
        assertEquals("1", var(p.pilha().get(0).locais(), "x"));
        d.parar();
    }

    @Test
    void passoSobrePulaACHamadaEPassoForaSaiDaFuncao() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(5));
        proxima();
        d.comando(PlDebugger.Comando.PASSO_SOBRE);
        PlDebugger.Pausa sobre = proxima();
        assertEquals(6, sobre.pilha().get(0).linha());
        assertEquals(1, sobre.pilha().size());
        assertEquals("2", var(sobre.pilha().get(0).locais(), "a"));
        d.comando(PlDebugger.Comando.CONTINUAR);
        fim();

        pausas.clear();
        resultado.set(null);
        PlDebugger d2 = iniciar(SRC, Set.of(2));
        proxima();
        d2.comando(PlDebugger.Comando.PASSO_FORA);
        PlDebugger.Pausa fora = proxima();
        assertEquals("main", fora.pilha().get(0).funcao());
        assertEquals(6, fora.pilha().get(0).linha());
        d2.comando(PlDebugger.Comando.CONTINUAR);
        assertEquals(4, fim().asLong());
    }

    @Test
    void pararNaEntradaPausaNoPrimeiroComandoDoCorpo() throws Exception {
        PlDebugger d = iniciar(LOOP, Pl.limites(), 10_000, Set.of(), true);
        // a primeira pausa e o "def main" do modulo (linha 1); em seguida o corpo
        PlDebugger.Pausa p = proxima();
        assertEquals("step", p.motivo());
        d.comando(PlDebugger.Comando.PASSO_DENTRO);
        PlDebugger.Pausa q = proxima();
        assertEquals(2, q.pilha().get(0).linha());
        d.comando(PlDebugger.Comando.CONTINUAR);
        assertEquals(3, fim().asLong());
    }

    @Test
    void breakpointEmLacoParaACadaIteracao() throws Exception {
        PlDebugger d = iniciar(LOOP, Set.of(4));
        for (int i = 0; i < 3; i++) {
            PlDebugger.Pausa p = proxima();
            assertEquals(String.valueOf(i), var(p.pilha().get(0).locais(), "i"));
            d.comando(PlDebugger.Comando.CONTINUAR);
        }
        assertEquals(3, fim().asLong());
    }

    @Test
    void globaisAparecemNaPausa() throws Exception {
        String src = "LIMITE = 7\ndef main():\n    x = LIMITE + 1\n    return x\n";
        PlDebugger d = iniciar(src, Set.of(4));
        PlDebugger.Pausa p = proxima();
        assertEquals("7", var(p.globais(), "LIMITE"));
        d.comando(PlDebugger.Comando.CONTINUAR);
        fim();
    }

    @Test
    void avaliaExpressaoSemAlterarOEstado() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(6));
        proxima();
        assertEquals(12, d.avaliar("a + 10").asLong());
        assertEquals(101, d.avaliar("[a for a in [99]][0] + 2").asLong());
        // a avaliacao trabalha numa copia: a continua valendo 2 e o resultado final nao muda
        assertEquals(2, d.avaliar("a").asLong());
        PlDebugError e = assertThrows(PlDebugError.class, () -> d.avaliar("a +"));
        assertTrue(e.mensagem().contains("invalida"), e.mensagem());
        assertThrows(PlError.class, () -> d.avaliar("nome_inexistente"));
        assertThrows(PlDebugError.class, () -> d.avaliar("1\n2"));
        d.comando(PlDebugger.Comando.CONTINUAR);
        assertEquals(4, fim().asLong());
        assertThrows(PlDebugError.class, () -> d.avaliar("1"));
    }

    @Test
    void comandoForaDePausaEErro() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(6));
        proxima();
        d.comando(PlDebugger.Comando.CONTINUAR);
        fim();
        assertThrows(PlDebugError.class, () -> d.comando(PlDebugger.Comando.CONTINUAR));
    }

    @Test
    void pararEncerraExecucaoPausada() throws Exception {
        PlDebugger d = iniciar(SRC, Set.of(6));
        proxima();
        d.parar();
        thread.join(5000);
        PlLimitError e = assertInstanceOf(PlLimitError.class, resultado.get());
        assertEquals(PlLimitError.Tipo.CANCELADO, e.limite());
        assertFalse(d.expirou());
    }

    @Test
    void ociosidadeCancelaAExecucao() throws Exception {
        PlDebugger d = iniciar(SRC, Pl.limites(), 150, Set.of(6), false);
        proxima();
        thread.join(5000);
        PlLimitError e = assertInstanceOf(PlLimitError.class, resultado.get());
        assertTrue(e.mensagem().contains("ociosidade"), e.mensagem());
        assertTrue(d.expirou());
    }

    @Test
    void tempoParadoNaoContaContraOPrazo() throws Exception {
        PlLimits lim = Pl.limites().prazoNanos(300_000_000L);
        PlDebugger d = iniciar(SRC, lim, 10_000, Set.of(6), false);
        proxima();
        Thread.sleep(700);
        d.comando(PlDebugger.Comando.CONTINUAR);
        assertEquals(4, fim().asLong());
        assertTrue(lim.creditoNanos() >= 600_000_000L);
    }

    @Test
    void semDepuracaoOPrazoContinuaValendo() {
        String src = "def main():\n    while True:\n        pass\n";
        PlLimits lim = Pl.limites().prazoNanos(100_000_000L);
        PlLimitError e = assertThrows(PlLimitError.class, () -> Pl.run(src, "main", lim, null,
                new PlHostMemoria(), null));
        assertEquals(PlLimitError.Tipo.TEMPO, e.limite());
    }

    @Test
    void pausaPedidaParaNoProximoComando() throws Exception {
        String src = "def main():\n    t = 0\n    for i in range(100000000):\n        t = t + 1\n    return t\n";
        PlLimits lim = PlLimits.padrao().maxPassos(Long.MAX_VALUE / 2).maxProfundidade(50);
        PlDebugger d = iniciar(src, lim, 10_000, Set.of(), false);
        Thread.sleep(50);
        d.pausar();
        PlDebugger.Pausa p = proxima();
        assertEquals("pause", p.motivo());
        d.parar();
    }

    @Test
    void doisDepuradoresSimultaneosNaoSeInterferem() throws Exception {
        PlProgram p = PlCompiler.compile(SRC, "teste.py");
        BlockingQueue<PlDebugger.Pausa> pa = new LinkedBlockingQueue<>();
        BlockingQueue<PlDebugger.Pausa> pb = new LinkedBlockingQueue<>();
        AtomicReference<Object> ra = new AtomicReference<>();
        AtomicReference<Object> rb = new AtomicReference<>();
        PlDebugger da = new PlDebugger("teste.py", 0, Pl.limites(), null, 0);
        PlDebugger db = new PlDebugger("teste.py", 0, Pl.limites(), null, 0);
        da.breakpoints(Set.of(6));
        db.breakpoints(Set.of(2));
        da.aoPausar(pa::add);
        db.aoPausar(pb::add);
        Thread ta = new Thread(() -> ra.set(new PlRuntime().call(p, "main", List.of(), null, null, Pl.limites(), da)));
        Thread tb = new Thread(() -> rb.set(new PlRuntime().call(p, "main", List.of(), null, null, Pl.limites(), db)));
        ta.start();
        tb.start();
        PlDebugger.Pausa xa = pa.poll(5, TimeUnit.SECONDS);
        PlDebugger.Pausa xb = pb.poll(5, TimeUnit.SECONDS);
        assertNotNull(xa);
        assertNotNull(xb);
        assertEquals("main", xa.pilha().get(0).funcao());
        assertEquals("f", xb.pilha().get(0).funcao());
        assertEquals(1, da.breakpoints().size());
        db.comando(PlDebugger.Comando.CONTINUAR);
        tb.join(5000);
        assertEquals(PlDebugger.Estado.PAUSADO, da.estado());
        assertEquals(4, ((AxonValue) rb.get()).asLong());
        da.comando(PlDebugger.Comando.CONTINUAR);
        ta.join(5000);
        assertEquals(4, ((AxonValue) ra.get()).asLong());
    }

    @Test
    void perfiladorContaExecucoesPorLinha() {
        PlProfiler pr = new PlProfiler();
        PlProgram p = PlCompiler.compile(LOOP, "teste.py");
        new PlRuntime().call(p, "main", List.of(), null, new PlHostMemoria(), Pl.limites(), pr);
        PlProfiler.Linha corpo = pr.linhas().stream().filter(l -> l.linha() == 4).findFirst().orElseThrow();
        assertEquals(3, corpo.execucoes());
        assertTrue(corpo.nanos() >= 0);
        assertEquals(1, pr.linhas().stream().filter(l -> l.linha() == 5).findFirst().orElseThrow().execucoes());
    }

    @Test
    void renderizaComLimites() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append('x');
        }
        String r = PlRender.valor(AxonValue.str(sb.toString()), 50, 2);
        assertTrue(r.length() <= 53, r);
        assertTrue(r.endsWith("..."));
    }
}
