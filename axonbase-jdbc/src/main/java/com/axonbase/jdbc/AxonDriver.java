package com.axonbase.jdbc;

import com.axonbase.common.Messages;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Driver JDBC do AxonBase.
 *
 * <p>URL de conexão: {@code jdbc:axonbase:wss://host:porta/rpc/ws?ns=app&db=main}
 *
 * <p>Registro: {@code Class.forName("com.axonbase.jdbc.AxonDriver")}
 */
public class AxonDriver implements Driver {

    private static final String PREFIX = "jdbc:axonbase:";
    private static final AxonDriver INSTANCE = new AxonDriver();

    static {
        try {
            DriverManager.registerDriver(INSTANCE);
        } catch (SQLException e) {
            throw new RuntimeException(Messages.get("jdbc_driver_registration_failed"), e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        return new AxonConnection(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[] {
                property("ns", info, "Namespace do AxonBase."),
                property("db", info, "Banco de dados do AxonBase."),
                property("user", info, "Usuário do AxonBase."),
                property("password", info, "Senha ou credencial temporária do AxonBase."),
                property("keystore", info, "Caminho para o keystore PKCS#12 do certificado de cliente."),
                property("keystorePassword", info, "Senha do keystore do certificado de cliente."),
                property("truststore", info, "Caminho para o truststore JKS que valida o certificado do servidor."),
                property("truststorePassword", info, "Senha do truststore do servidor.")
        };
    }

    private static DriverPropertyInfo property(String name, Properties info, String description) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, info.getProperty(name));
        property.description = description;
        property.required = false;
        return property;
    }

    @Override
    public int getMajorVersion() { return 0; }

    @Override
    public int getMinorVersion() { return 2; }

    @Override
    public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException(Messages.get("jdbc_parent_logger_unsupported"));
    }
}
