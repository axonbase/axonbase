package com.axonbase.pl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Base de todos os erros do AxonPL. Nao preenche stack trace Java (barato de lancar). */
public abstract class PlError extends RuntimeException {

    private final String tipo;
    private final String mensagem;
    private final ArrayList<PlTraceFrame> quadros = new ArrayList<>();
    private boolean notificado;

    protected PlError(String tipo, String mensagem) {
        super(tipo + ": " + mensagem, null, false, false);
        this.tipo = tipo;
        this.mensagem = mensagem;
    }

    /** Nome do tipo do erro (por exemplo ValueError). */
    public String tipo() {
        return tipo;
    }

    /** Mensagem sem o nome do tipo. */
    public String mensagem() {
        return mensagem;
    }

    /** Traceback estruturado, do quadro mais externo para o mais interno. */
    public List<PlTraceFrame> traceback() {
        ArrayList<PlTraceFrame> c = new ArrayList<>(quadros);
        Collections.reverse(c);
        return c;
    }

    /** Uso interno: registra um quadro durante o desenrolar da pilha (do mais interno ao mais externo). */
    public void registrarQuadro(PlTraceFrame q) {
        quadros.add(q);
    }

    /** Uso interno: marca que o hook de depuracao ja foi avisado. Devolve o estado anterior. */
    public boolean marcarNotificado() {
        boolean a = notificado;
        notificado = true;
        return a;
    }

    /** Traceback formatado em texto, no estilo do Python. */
    public String tracebackTexto() {
        StringBuilder sb = new StringBuilder("Traceback (chamada mais recente por ultimo):\n");
        for (PlTraceFrame q : traceback()) {
            sb.append("  Arquivo \"").append(q.programa()).append("\", linha ").append(q.linha())
                    .append(", coluna ").append(q.coluna()).append(", em ").append(q.funcao()).append('\n');
        }
        return sb.append(tipo).append(": ").append(mensagem).toString();
    }
}
