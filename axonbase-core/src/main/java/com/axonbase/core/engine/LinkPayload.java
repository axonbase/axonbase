package com.axonbase.core.engine;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * O que um comando encaminhado por um DATABASE LINK pode conter. No outro servidor ele roda com a credencial da
 * ligação, então uma sentença aninhada ou uma função com efeito daria essa credencial a quem só pode consultar aqui.
 *
 * <p>A conferência lê o texto que vai ser enviado e percorre a árvore inteira. Os {@code switch} não têm
 * {@code default}: um nó novo na árvore não compila enquanto não for classificado.</p>
 */
final class LinkPayload {

    /**
     * Funções sem efeito, nome por nome: um nome fora da lista pode ser um programa do usuário no participante. Ficam
     * de fora {@code kv::*}, {@code graph::*}, {@code vector::embed}, {@code vector::fromstr}, {@code shard::*} e todo
     * programa.
     */
    private static final Set<String> PURE_FUNCTIONS = Set.of(
        "string::concat", "string::contains", "string::ends_with", "string::starts_with",
        "string::join", "string::len", "string::length", "string::lowercase", "string::uppercase",
        "string::repeat", "string::replace", "string::reverse", "string::slice", "string::split",
        "string::trim", "string::words", "string::matches", "string::slug", "string::capitalize",
        "string::pad_start", "string::pad_end",
        "math::abs", "math::ceil", "math::floor", "math::round", "math::fixed", "math::sqrt",
        "math::pow", "math::ln", "math::log", "math::log10", "math::log2", "math::exp",
        "math::sign", "math::clamp", "math::min", "math::max", "math::sum", "math::mean",
        "math::median", "math::product",
        "count", Expr.COUNT_DISTINCT, "sum", "avg", "min", "max",
        "array::add", "array::append", "array::push", "array::prepend", "array::all", "array::any",
        "array::at", "array::concat", "array::complement", "array::difference", "array::distinct",
        "array::find_index", "array::first", "array::last", "array::flatten", "array::insert",
        "array::intersect", "array::join", "array::len", "array::length", "array::max", "array::min",
        "array::pop", "array::remove", "array::reverse", "array::shuffle", "array::slice",
        "array::sort", "array::sort::asc", "array::sort::desc", "array::union",
        "object::keys", "object::values", "object::len", "object::length", "object::entries",
        "object::from_entries",
        "type::bool", "type::int", "type::float", "type::number", "type::decimal", "type::string",
        "type::datetime", "type::table", "type::record", "type::array", "type::is::array",
        "type::is::bool", "type::is::datetime", "type::is::none", "type::is::null",
        "type::is::number", "type::is::object", "type::is::record", "type::is::string",
        "record::id", "record::table", "record::tb", "record::exists", "meta::id", "meta::tb",
        "geometry::point", "geometry::line", "geometry::polygon", "geo::distance", "geo::area",
        "geo::contains", "geo::cell", "geo::h3", "geo::h3::center", "geo::h3::boundary", "geo::h3::resolution",
        "geo::h3::parent", "geo::h3::children", "geo::h3::disk",
        "vector::distance::euclidean", "vector::distance::manhattan",
        "vector::distance::cosine",
        "vector::similarity::cosine",
        "search::score", "search::highlight",
        "time::now", "now", "time::unix", "time::millis", "time::year", "time::month", "time::day",
        "time::hour", "time::minute", "time::second", "time::wday", "time::yday", "time::clock", "time::floor",
        "time::from::unix", "time::from::millis",
        "rand", "rand::bool", "rand::float", "rand::int", "rand::string", "rand::uuid", "rand::enum",
        "crypto::md5", "crypto::sha1", "crypto::sha256", "crypto::sha512",
        "encoding::base64::encode", "encoding::base64::decode",
        "value::coalesce", "value::default");

    private LinkPayload() {
    }

    /**
     * O motivo para não enviar o texto, ou vazio quando ele é uma única sentença sem nada aninhado nem com efeito. O
     * motivo cita só o tipo do nó ou o nome da função, nunca valores do comando.
     */
    static Optional<String> refusal(String axonql) {
        try {
            List<Statement> statements = AxonQl.parse(axonql).statements();
            if (statements.size() != 1) {
                return Optional.of("é preciso exatamente uma sentença");
            }
            top(statements.get(0));
            return Optional.empty();
        } catch (Refused e) {
            return Optional.of(e.getMessage());
        } catch (RuntimeException e) {
            // o que não se consegue ler não segue
            return Optional.of("o texto não pôde ser conferido");
        }
    }

    /** A sentença enviada: só as que as rotas de DATABASE LINK e de SAGA montam. */
    private static void top(Statement statement) {
        switch (statement) {
            case Statement.Select x -> select(x);
            case Statement.Create x -> {
                expr(x.target());
                data(x.data());
                returning(x.ret());
            }
            case Statement.Update x -> {
                expr(x.target());
                data(x.data());
                expr(x.cond());
                returning(x.ret());
            }
            case Statement.Delete x -> {
                expr(x.target());
                expr(x.cond());
                returning(x.ret());
            }
            case Statement.Relate x -> {
                expr(x.from());
                expr(x.kind());
                expr(x.to());
                data(x.data());
                returning(x.ret());
            }
            case Statement.Unrelate x -> {
                expr(x.from());
                expr(x.kind());
                expr(x.to());
            }
            // a coordenação de uma SAGA leva apenas nomes
            case Statement.BeginSaga x -> { }
            case Statement.CommitSaga x -> { }
            case Statement.CancelSaga x -> { }
            case Statement.ShowSagaTransaction x -> { }
            case Statement.Use x -> refuse(x);
            case Statement.Let x -> refuse(x);
            case Statement.CreateJks x -> refuse(x);
            case Statement.Insert x -> refuse(x);
            case Statement.Join x -> refuse(x);
            case Statement.Live x -> refuse(x);
            case Statement.Explain x -> refuse(x);
            case Statement.Partition x -> refuse(x);
            case Statement.DefineTable x -> refuse(x);
            case Statement.CreateTable x -> refuse(x);
            case Statement.DefineField x -> refuse(x);
            case Statement.DefineEmbedding x -> refuse(x);
            case Statement.DropEmbedding x -> refuse(x);
            case Statement.Reembed x -> refuse(x);
            case Statement.DefineIndex x -> refuse(x);
            case Statement.DefineAnalyzer x -> refuse(x);
            case Statement.DefineEvent x -> refuse(x);
            case Statement.DefineUser x -> refuse(x);
            case Statement.DefineAccess x -> refuse(x);
            case Statement.DefineDatabaseLink x -> refuse(x);
            case Statement.DropDatabaseLink x -> refuse(x);
            case Statement.CreateSaga x -> refuse(x);
            case Statement.DescribeSaga x -> refuse(x);
            case Statement.DescribeTable x -> refuse(x);
            case Statement.JoinSaga x -> refuse(x);
            case Statement.LeaveSaga x -> refuse(x);
            case Statement.Info x -> refuse(x);
            case Statement.Return x -> refuse(x);
            case Statement.IfElse x -> refuse(x);
            case Statement.Kill x -> refuse(x);
            case Statement.ErrorStmt x -> refuse(x);
            case Statement.Begin x -> refuse(x);
            case Statement.Commit x -> refuse(x);
            case Statement.Cancel x -> refuse(x);
            case Statement.Savepoint x -> refuse(x);
            case Statement.Release x -> refuse(x);
            case Statement.RollbackTo x -> refuse(x);
            case Statement.CreateDataRule x -> refuse(x);
            case Statement.DropDataRule x -> refuse(x);
            case Statement.ShowDataRules x -> refuse(x);
            case Statement.DefineAiAudit x -> refuse(x);
            case Statement.DropAiAudit x -> refuse(x);
            case Statement.RemoveTable x -> refuse(x);
            case Statement.DropTable x -> refuse(x);
            case Statement.AlterTable x -> refuse(x);
            case Statement.CreateIndex x -> refuse(x);
            case Statement.DropIndex x -> refuse(x);
            case Statement.CreateEvent x -> refuse(x);
            case Statement.DropEvent x -> refuse(x);
            case Statement.CreateAnalyzer x -> refuse(x);
            case Statement.DropAnalyzer x -> refuse(x);
            case Statement.CreateUser x -> refuse(x);
            case Statement.DropUser x -> refuse(x);
            case Statement.GrantAccess x -> refuse(x);
            case Statement.RevokeAccess x -> refuse(x);
            case Statement.CreateDatabaseLink x -> refuse(x);
            case Statement.AlterDatabaseLink x -> refuse(x);
            case Statement.DropSaga x -> refuse(x);
            case Statement.ShowAiAudit x -> refuse(x);
            case Statement.SetReasonAudit x -> refuse(x);
            case Statement.SetAuditCase x -> refuse(x);
            case Statement.CreateFunction x -> refuse(x);
            case Statement.CreateProcedure x -> refuse(x);
            case Statement.CreatePackage x -> refuse(x);
            case Statement.DropProgram x -> refuse(x);
            case Statement.CallProgram x -> refuse(x);
        }
    }

    /** FETCH leva só nomes, sem expressão a percorrer. */
    private static void select(Statement.Select select) {
        all(select.fields());
        expr(select.from());
        expr(select.cond());
        if (select.orders() != null) {
            select.orders().forEach(order -> expr(order.field()));
        }
        all(select.group());
        expr(select.limit());
        expr(select.start());
        if (select.joins() != null) {
            for (Statement.Join join : select.joins()) {
                expr(join.source());
                expr(join.left());
                expr(join.right());
            }
        }
        if (select.timeTravel() != null) {
            expr(select.timeTravel().value());
        }
        expr(select.having());
    }

    private static void data(Statement.Data data) {
        if (data == null) {
            return;
        }
        switch (data) {
            case Statement.Data.Content x -> expr(x.content());
            case Statement.Data.SetClause x -> {
                for (Statement.Assignment assignment : x.assignments()) {
                    expr(assignment.path());
                    expr(assignment.value());
                }
            }
            case Statement.Data.Merge x -> expr(x.merge());
            case Statement.Data.Patch x -> expr(x.patch());
            case Statement.Data.Replace x -> expr(x.replace());
        }
    }

    private static void returning(Statement.ReturnSpec ret) {
        if (ret != null) {
            expr(ret.expr());
        }
    }

    private static void all(List<Expr> expressions) {
        if (expressions != null) {
            expressions.forEach(LinkPayload::expr);
        }
    }

    /** {@code null} é uma cláusula ausente. */
    private static void expr(Expr expr) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expr.Literal x -> { }
            case Expr.Ident x -> { }
            case Expr.Path x -> { }
            // seria resolvido no participante, com as variáveis da sessão da ligação
            case Expr.Param x -> refuse("parâmetro $" + x.name());
            // seria outra ligação ou outro banco do participante
            case Expr.Qualified x -> refuse("nome qualificado " + x.link() + "." + x.table());
            case Expr.Block x -> refuse("bloco de sentenças");
            case Expr.SubQuery x -> refuse("subconsulta");
            case Expr.Call x -> {
                if (!PURE_FUNCTIONS.contains(x.name())) {
                    refuse("função " + x.name());
                }
                all(x.args());
            }
            case Expr.RecordId x -> {
                expr(x.table());
                expr(x.key());
            }
            case Expr.ObjectLit x -> x.entries().forEach(entry -> expr(entry.value()));
            case Expr.ArrayLit x -> all(x.items());
            case Expr.SetLit x -> all(x.items());
            case Expr.Unary x -> expr(x.operand());
            case Expr.Binary x -> {
                expr(x.left());
                expr(x.right());
            }
            case Expr.Cast x -> expr(x.operand());
            case Expr.Idiom x -> {
                expr(x.base());
                x.parts().forEach(LinkPayload::part);
            }
            case Expr.Range x -> {
                expr(x.start());
                expr(x.end());
            }
            case Expr.Alias x -> expr(x.expr());
        }
    }

    private static void part(Expr.Part part) {
        switch (part) {
            case Expr.Part.Field x -> { }
            case Expr.Part.All x -> { }
            case Expr.Part.First x -> { }
            case Expr.Part.Last x -> { }
            case Expr.Part.Index x -> expr(x.index());
            case Expr.Part.Where x -> expr(x.cond());
            case Expr.Part.Method x -> refuse("chamada de método " + x.name());
            case Expr.Part.Graph x -> expr(x.lookup());
        }
    }

    private static void refuse(Statement statement) {
        refuse("sentença " + statement.getClass().getSimpleName());
    }

    private static void refuse(String reason) {
        throw new Refused(reason);
    }

    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refused(String reason) {
            super(reason, null, false, false);
        }
    }
}
