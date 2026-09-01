package com.axonbase.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import org.junit.jupiter.api.Test;

class DatabaseLinkParserTest {

    private static Statement firstStmt(String sql) {
        return AxonQl.parse(sql).statements().get(0);
    }

    @Test
    void parseQualifiedTable() {
        Statement.Select sel = (Statement.Select) firstStmt("SELECT * FROM \"remote1\".\"users\"");
        assertInstanceOf(Expr.Qualified.class, sel.from());
        Expr.Qualified qual = (Expr.Qualified) sel.from();
        assertEquals("remote1", qual.link());
        assertEquals("users", qual.table());
    }

    @Test
    void parseQualifiedRecordId() {
        Statement.Select sel = (Statement.Select) firstStmt("SELECT * FROM \"europe\".\"person\":1");
        assertInstanceOf(Expr.RecordId.class, sel.from());
        Expr.RecordId rid = (Expr.RecordId) sel.from();
        assertInstanceOf(Expr.Qualified.class, rid.table());
        Expr.Qualified qual = (Expr.Qualified) rid.table();
        assertEquals("europe", qual.link());
        assertEquals("person", qual.table());
    }

    @Test
    void parseQualifiedJoin() {
        String sql = "SELECT u.name, o.total FROM \"europe\".\"users\" u JOIN \"europe\".\"orders\" o ON u.id = o.user_id WHERE u.age > 25";
        Statement.Select sel = (Statement.Select) firstStmt(sql);
        assertInstanceOf(Expr.Qualified.class, sel.from());
        Expr.Qualified from = (Expr.Qualified) sel.from();
        assertEquals("europe", from.link());
        assertEquals("users", from.table());
        assertEquals(1, sel.joins().size());
        Statement.Join join = sel.joins().get(0);
        assertInstanceOf(Expr.Qualified.class, join.source());
        Expr.Qualified joinSource = (Expr.Qualified) join.source();
        assertEquals("europe", joinSource.link());
        assertEquals("orders", joinSource.table());
    }

    @Test
    void parseDefineDatabaseLink() {
        Statement.DefineDatabaseLink ddl = (Statement.DefineDatabaseLink) firstStmt(
            "DEFINE DATABASE LINK \"europe\" CONNECT BY \"ws://10.0.0.2:8000/rpc/ws\" WITH ns = \"prod\" db = \"main\" user = \"svc\" password = \"secret\"");
        assertEquals("europe", ddl.name());
        assertEquals("ws://10.0.0.2:8000/rpc/ws", ddl.url());
        assertEquals("prod", ddl.ns());
        assertEquals("main", ddl.db());
        assertEquals("svc", ddl.user());
        assertEquals("secret", ddl.password());
    }

    @Test
    void parseDropDatabaseLink() {
        Statement.DropDatabaseLink ddl = (Statement.DropDatabaseLink) firstStmt("DROP DATABASE LINK \"europe\"");
        assertEquals("europe", ddl.name());
    }

    @Test
    void localTableStillWorks() {
        Statement.Select sel = (Statement.Select) firstStmt("SELECT * FROM users");
        assertInstanceOf(Expr.Ident.class, sel.from());
        assertEquals("users", ((Expr.Ident) sel.from()).name());
    }

    @Test
    void renderDefineDatabaseLink() {
        var ddl = new Statement.DefineDatabaseLink("europe", "ws://host:8000/rpc/ws", "prod", "main", "svc", "secret");
        String rendered = com.axonbase.parser.Render.stmt(ddl);
        assertTrue(rendered.contains("DEFINE DATABASE LINK"));
        assertTrue(rendered.contains("\"europe\""));
        assertTrue(rendered.contains("ws://host:8000/rpc/ws"));
    }
}