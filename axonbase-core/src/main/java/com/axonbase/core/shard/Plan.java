package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * O que uma instrução faz nos shards: um trecho por shard e como juntar as respostas, na ordem dos trechos.
 *
 * @param parts trechos a enviar; vazio quando o gateway já tem a resposta
 * @param merge junta as respostas
 * @param write a instrução grava: os trechos vão um de cada vez, e uma falha no meio é uma escrita parcial
 */
record Plan(List<Part> parts, Function<List<AxonValue>, AxonValue> merge, boolean write) {

    /**
     * @param vars    variáveis próprias deste trecho, além das da sessão
     * @param partial executa em modo parcial (consulta espalhada)
     * @param filter  slots que valem na resposta, durante uma movimentação
     */
    record Part(String shard, String text, Map<String, AxonValue> vars, boolean partial, SlotFilter filter) {

        static Part of(String shard, String text) {
            return new Part(shard, text, Map.of(), false, null);
        }

        static Part of(String shard, String text, Map<String, AxonValue> vars) {
            return new Part(shard, text, vars, false, null);
        }
    }

    Plan {
        parts = List.copyOf(parts);
    }

    /** Resposta pronta no gateway. */
    static Plan local(AxonValue value) {
        return new Plan(List.of(), ignored -> value, false);
    }

    static Plan read(String shard, String text) {
        return new Plan(List.of(Part.of(shard, text)), replies -> replies.get(0), false);
    }

    static Plan write(String shard, String text) {
        return write(Part.of(shard, text));
    }

    static Plan write(Part part) {
        return new Plan(List.of(part), replies -> replies.get(0), true);
    }
}
