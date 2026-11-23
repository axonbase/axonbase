package com.axonbase.core.security;

import com.axonbase.value.AxonValue;

import java.util.Locale;

/**
 * Regras de papel e escopo aplicadas sobre os claims da sessão ({@code session.auth()}).
 *
 * <p>Sessão sem auth (modo embutido ou servidor sem autenticação) não é restringida:
 * quem decide exigir autenticação é o servidor, antes de chegar ao motor.</p>
 */
public final class Authz {

    /** Papéis em ordem crescente de poder. */
    public enum Role { NONE, VIEWER, OPERATOR, ADMIN }

    private Authz() {
    }

    public static boolean authenticated(AxonValue auth) {
        return auth != null && auth.isObject();
    }

    /** Escopo dos claims; ausente conta como ROOT por compatibilidade com tokens antigos. */
    public static AuthCatalog.Scope scope(AxonValue auth) {
        String s = str(auth, "scope");
        if (s.isEmpty()) {
            return AuthCatalog.Scope.ROOT;
        }
        try {
            return AuthCatalog.Scope.valueOf(s);
        } catch (IllegalArgumentException e) {
            return AuthCatalog.Scope.DATABASE;
        }
    }

    /**
     * Papel efetivo. Os papéis reconhecidos são viewer, operator, admin (e owner, que vale admin).
     * Sem nenhum papel reconhecido, o escopo ROOT vale admin (identidades antigas) e os demais valem NONE.
     */
    public static Role role(AxonValue auth) {
        if (!authenticated(auth)) {
            return Role.NONE;
        }
        Role best = Role.NONE;
        boolean recognized = false;
        for (String r : str(auth, "roles").split("[,\\s]+")) {
            Role mapped = switch (r.toLowerCase(Locale.ROOT)) {
                case "admin", "owner" -> Role.ADMIN;
                case "operator" -> Role.OPERATOR;
                case "viewer" -> Role.VIEWER;
                default -> null;
            };
            if (mapped != null) {
                recognized = true;
                if (mapped.compareTo(best) > 0) {
                    best = mapped;
                }
            }
        }
        if (!recognized && scope(auth) == AuthCatalog.Scope.ROOT) {
            return Role.ADMIN;
        }
        return best;
    }

    public static boolean atLeast(AxonValue auth, Role min) {
        return role(auth).compareTo(min) >= 0;
    }

    /** Namespace do claim, ou string vazia. */
    public static String ns(AxonValue auth) {
        return str(auth, "ns");
    }

    public static String db(AxonValue auth) {
        return str(auth, "db");
    }

    private static String str(AxonValue auth, String key) {
        if (!authenticated(auth)) {
            return "";
        }
        AxonValue v = auth.asObject().get(key);
        return v != null && v.isString() ? v.asString() : "";
    }
}
