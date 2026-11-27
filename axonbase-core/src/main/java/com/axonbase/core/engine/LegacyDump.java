package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.Map;
import java.util.Set;

/**
 * Compatibilidade com os dumps antigos, que escreviam o conteúdo dos registros em JSON: decimais, datas e outros
 * tipos saíam num objeto marcado ({@code {"$decimal": "..."}}), que a AxonQL lê como objeto. Na importação esses
 * objetos voltam a ser o valor tipado antes da escrita, para índices e campos verem o tipo certo.
 *
 * <p>Não muda o que fica guardado: o objeto marcado já era lido do armazenamento como o valor tipado.</p>
 */
final class LegacyDump {

    private static final Set<String> TAGS =
        Set.of("$decimal", "$datetime", "$duration", "$uuid", "$bytes", "$table", "$record");

    private LegacyDump() {
    }

    static Query restoreTypes(Query query) {
        return new Query(query.statements().stream().map(LegacyDump::restoreStatement).toList());
    }

    private static Statement restoreStatement(Statement statement) {
        if (statement instanceof Statement.Create create && create.data() instanceof Statement.Data.Content data) {
            return new Statement.Create(create.only(), create.target(),
                new Statement.Data.Content(restoreExpr(data.content())), create.ret());
        }
        return statement;
    }

    private static Expr restoreExpr(Expr expr) {
        if (expr instanceof Expr.ArrayLit array) {
            return new Expr.ArrayLit(array.items().stream().map(LegacyDump::restoreExpr).toList());
        }
        if (expr instanceof Expr.ObjectLit object) {
            Expr typed = typedValue(object);
            if (typed != null) {
                return typed;
            }
            return new Expr.ObjectLit(object.entries().stream()
                .map(entry -> new Expr.ObjectLit.Entry(entry.key(), restoreExpr(entry.value())))
                .toList());
        }
        return expr;
    }

    /** O valor de um objeto marcado, ou {@code null} quando o objeto é um objeto mesmo. */
    private static Expr typedValue(Expr.ObjectLit object) {
        if (object.entries().size() != 1) {
            return null;
        }
        Expr.ObjectLit.Entry entry = object.entries().get(0);
        if (!TAGS.contains(entry.key()) || !(entry.value() instanceof Expr.Literal literal)
            || !literal.value().isString()) {
            return null;
        }
        try {
            // a mesma leitura que o armazenamento faz do objeto marcado
            String json = AxonJson.write(AxonValue.object(Map.of(entry.key(), literal.value())));
            return new Expr.Literal(AxonJson.parseDocument(json));
        } catch (AxonError invalid) {
            // conteúdo que não é do tipo marcado: segue como objeto, como antes
            return null;
        }
    }
}
