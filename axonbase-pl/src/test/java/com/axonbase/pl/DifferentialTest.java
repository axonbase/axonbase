package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.value.AxonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Testes diferenciais contra o CPython. Os resultados esperados vem de diff/expected.json, gravado por
 * scripts/gen_expected.py. Com -Daxon.pl.live=true compara tambem ao vivo com o python3 da maquina.
 */
class DifferentialTest {

    private static final Pattern MARCA = Pattern.compile("^#=== (\\S+)\\s*$");
    private static final boolean LIVE = Boolean.getBoolean("axon.pl.live");

    record Caso(String nome, String arquivo, String fonte) {
    }

    static String lerRecurso(String nome) throws IOException {
        try (var in = DifferentialTest.class.getResourceAsStream("/diff/" + nome)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<Caso> corpus() throws IOException {
        List<Caso> out = new ArrayList<>();
        for (String arq : List.of("aritmetica.pyt", "strings.pyt", "colecoes.pyt", "fluxo.pyt", "decimal_json.pyt",
                "sweeps.pyt")) {
            String nome = null;
            StringBuilder sb = new StringBuilder();
            for (String linha : lerRecurso(arq).split("\n", -1)) {
                Matcher m = MARCA.matcher(linha);
                if (m.matches()) {
                    if (nome != null) {
                        out.add(new Caso(nome, arq, sb.toString()));
                    }
                    nome = m.group(1);
                    sb.setLength(0);
                } else {
                    sb.append(linha).append('\n');
                }
            }
            if (nome != null) {
                out.add(new Caso(nome, arq, sb.toString()));
            }
        }
        return out;
    }

    /** Executa o corpo numa thread com pilha grande (recursao funda) e devolve o resultado ou o erro. */
    static Map<String, Object> executar(String fonte) {
        AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            Map<String, Object> r = new LinkedHashMap<>();
            PlHostMemoria host = new PlHostMemoria();
            try {
                PlProgram p = PlCompiler.compile(fonte + "\ndef __axon_run__():\n    return repr(main())\n", "corpus.py");
                AxonValue v = new PlRuntime().call(p, "__axon_run__", List.of(), null, host,
                        PlLimits.padrao().maxPassos(50_000_000L).maxProfundidade(400), null);
                r.put("resultado", v.asString());
            } catch (PlRuntimeError e) {
                r.put("erro", e.tipo());
                r.put("msg", e.mensagem());
            } catch (PlError e) {
                r.put("erro", e.tipo());
                r.put("msg", e.mensagem());
            }
            r.put("stdout", String.join("\n", host.logs()));
            ref.set(r);
        }, "dif", 512L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    static String stdoutNormalizado(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    @TestFactory
    Stream<DynamicTest> programasContraCPython() throws IOException {
        AxonValue exp = com.axonbase.value.AxonJson.parseDocument(lerRecurso("expected.json"));
        Map<String, AxonValue> porNome = new LinkedHashMap<>();
        for (AxonValue c : exp.asObject().get("casos").asArray()) {
            porNome.put(c.asObject().get("nome").asString(), c);
        }
        List<Caso> casos = corpus();
        assertTrue(casos.size() >= 250, "o corpus deve ter pelo menos 250 programas: " + casos.size());
        assertEquals(porNome.size(), casos.size(), "corpus e expected.json fora de sincronia (rode gen_expected.py)");
        return casos.stream().map(c -> DynamicTest.dynamicTest(c.nome(), () -> {
            AxonValue e = porNome.get(c.nome());
            assertTrue(e != null, "sem resultado esperado para " + c.nome());
            Map<String, AxonValue> eo = e.asObject();
            Map<String, Object> r = executar(c.fonte());
            if (eo.get("erro").isString()) {
                assertEquals(eo.get("erro").asString(), r.get("erro"), "tipo de erro em " + c.nome()
                        + " (msg: " + r.get("msg") + ")");
            } else {
                assertEquals(null, r.get("erro"), "erro inesperado em " + c.nome() + ": " + r.get("msg"));
                assertEquals(eo.get("resultado").asString(), r.get("resultado"), "resultado de " + c.nome());
                assertEquals(stdoutNormalizado(eo.get("stdout").asString()), r.get("stdout"), "stdout de " + c.nome());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> ao_vivo_contra_python3() throws IOException {
        if (!LIVE) {
            return Stream.empty();
        }
        String driver = "import sys,io,contextlib,json\n"
                + "src=open(sys.argv[1]).read()\n"
                + "ns={'__name__':'x'}\nbuf=io.StringIO()\n"
                + "try:\n"
                + "    code=compile(src,'<c>','exec')\n"
                + "    with contextlib.redirect_stdout(buf):\n"
                + "        exec(code,ns)\n        r=repr(ns['main']())\n"
                + "    print(json.dumps({'r':r}))\n"
                + "except BaseException as e:\n"
                + "    print(json.dumps({'e':type(e).__name__}))\n";
        Path drv = Files.createTempFile("axon-live", ".py");
        Files.writeString(drv, driver);
        return corpus().stream().map(c -> DynamicTest.dynamicTest("live " + c.nome(), () -> {
            Path src = Files.createTempFile("axon-prog", ".py");
            Files.writeString(src, c.fonte());
            Process p = new ProcessBuilder("python3", "-W", "ignore", drv.toString(), src.toString())
                    .redirectErrorStream(true).start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            AxonValue j = com.axonbase.value.AxonJson.parseDocument(saida);
            Map<String, Object> r = executar(c.fonte());
            if (j.asObject().containsKey("e")) {
                assertEquals(j.asObject().get("e").asString(), r.get("erro"));
            } else {
                assertEquals(j.asObject().get("r").asString(), r.get("resultado"));
            }
            Files.deleteIfExists(src);
        }));
    }
}
