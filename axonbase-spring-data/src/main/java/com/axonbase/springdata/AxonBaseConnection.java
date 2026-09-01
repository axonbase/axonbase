package com.axonbase.springdata;

import com.axonbase.sdk.Axon;

/** Creates an authenticated Axon client suitable for injection into Spring Data components. */
public final class AxonBaseConnection {
    private AxonBaseConnection() {
    }

    public static Axon connect(String url, String namespace, String database, String token) {
        return connect(url, namespace, database, token, null, null);
    }

    public static Axon connect(String url, String namespace, String database, String token,
                               String user, String password) {
        if (token != null && !token.isBlank() && (user != null || password != null)) {
            throw new IllegalArgumentException("Configure either a token or user credentials, not both");
        }
        if ((user == null) != (password == null)) {
            throw new IllegalArgumentException("Both user and password are required for authentication");
        }
        Axon axon = Axon.connect(url);
        try {
            if (token != null && !token.isBlank()) {
                axon.authenticate(token);
            } else if (user != null) {
                axon.signin(user, password);
            }
            axon.use(namespace, database);
            return axon;
        } catch (RuntimeException e) {
            axon.close();
            throw e;
        }
    }
}
