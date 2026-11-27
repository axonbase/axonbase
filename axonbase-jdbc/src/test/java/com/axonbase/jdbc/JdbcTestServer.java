package com.axonbase.jdbc;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Servidor em memória, numa porta livre, para os testes do driver rodarem sem ambiente externo. */
public final class JdbcTestServer implements AutoCloseable {

    private final AxonServer server;

    public JdbcTestServer() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        server = AxonServer.startRandomPort(ds, "secret-testing");
    }

    public String url(String extra) {
        return "jdbc:axonbase:ws://127.0.0.1:" + server.port() + "/rpc/ws?ns=test&db=dev" + extra;
    }

    public Connection connect() throws SQLException {
        new AxonDriver();
        return DriverManager.getConnection(url(""));
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }
}
