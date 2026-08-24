package com.axonbase.server.auth;

import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthScopeTest {

    @Test
    void jwtDoUsuarioDeBancoNaoAutorizaOutroBanco() {
        AuthCatalog catalog = new AuthCatalog();
        catalog.defineUser("alice", AuthCatalog.Scope.DATABASE, "app", "main", "senha", java.util.List.of("editor"));
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
}
