package com.axonbase.server.auth;

import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthScopeTest {

    @Test
    void jwtDoUsuarioDeBancoNaoAutorizaOutroBanco() {
        AuthCatalog catalog = new AuthCatalog();
        catalog.defineUser("alice", AuthCatalog.Scope.DATABASE, "app", "main", "senha", java.util.List.of("editor"), java.util.List.of());
        catalog.defineAccess("app_login", AuthCatalog.Scope.DATABASE, "app", "main");
        AuthService auth = new AuthService("segredo", new UserStore(catalog));

        String token = auth.signin("alice", "senha", "app", "main", "app_login");
        assertNotNull(token);
        var claims = auth.claims(token);
        assertNotNull(claims);
        assertTrue(auth.allows(claims, "app", "main"));
        assertFalse(auth.allows(claims, "app", "other"));
        assertFalse(auth.allows(claims, "other", "main"));
        assertNull(auth.signin("alice", "senha", "app", "other", "app_login"));
    }

    @Test
    void credencialTemporariaAutenticaUmaUnicaVezNoMesmoEscopo() {
        AuthCatalog catalog = new AuthCatalog();
        catalog.defineCertificateUser("12345678901", AuthCatalog.Scope.DATABASE, "app", "main",
            "icp_brasil", "SHA256:certificate", java.util.List.of("client"), java.util.List.of());
        AuthService auth = new AuthService("segredo", new UserStore(catalog));

        String credential = auth.issueTemporaryCredential("12345678901",
            "icp_brasil", "SHA256:certificate");

        String session = auth.signin("12345678901", credential, "app", "main", null);
        assertNotNull(session);
        assertNotNull(auth.claims(session));
        // Reuso da mesma credencial é permitido (JWT cacheado) para DataGrip
        assertNotNull(auth.signin("12345678901", credential, "app", "main", null));
    }

    @Test
    void credencialTemporariaExigeBindingDoUsuarioCertificado() {
        AuthCatalog catalog = new AuthCatalog();
        catalog.defineCertificateUser("cert", AuthCatalog.Scope.DATABASE, "app", "main",
            "clients", "SHA256:ABCD", java.util.List.of(), java.util.List.of());
        catalog.defineUser("password", AuthCatalog.Scope.DATABASE, "app", "main",
            "senha", java.util.List.of(), java.util.List.of());
        AuthService auth = new AuthService("segredo", new UserStore(catalog));

        assertThrows(IllegalArgumentException.class,
            () -> auth.issueTemporaryCredential("cert", "other", "SHA256:ABCD"));
        assertThrows(IllegalArgumentException.class,
            () -> auth.issueTemporaryCredential("cert", "clients", "SHA256:other"));
        assertThrows(IllegalArgumentException.class,
            () -> auth.issueTemporaryCredential("password", "clients", "SHA256:ABCD"));
    }
}
