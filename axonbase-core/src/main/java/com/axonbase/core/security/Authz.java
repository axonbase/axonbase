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

    /** O usuário tem alguma Data Rule atribuída. */
    public static boolean hasDataRules(AxonValue auth) {
        if (!authenticated(auth)) {
            return false;
        }
        AxonValue rules = auth.asObject().get("data_rules");
        if (rules == null) {
            return false;
        }
        return rules.isArray() ? !rules.asArray().isEmpty() : rules.isString() && !rules.asString().isEmpty();
    }

    /** O usuário está sob auditoria por IA ({@code AUDITED BY}). */
    public static boolean audited(AxonValue auth) {
        return !str(auth, "audit").isBlank();
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

    /**
     * Rebaixa o papel dos claims ao teto informado. Nunca eleva: papel NONE ou já
     * igual/menor que o teto passa intacto. Devolve uma cópia com {@code roles} trocado.
     */
    public static AxonValue cap(AxonValue auth, Role cap) {
        if (!authenticated(auth) || cap == null) {
            return auth;
        }
        Role current = role(auth);
        if (current == Role.NONE || current.compareTo(cap) <= 0) {
            return auth;
        }
        java.util.Map<String, AxonValue> copy = new java.util.LinkedHashMap<>(auth.asObject());
        copy.put("roles", AxonValue.str(cap.name().toLowerCase(Locale.ROOT)));
        return AxonValue.object(copy);
    }

    /** O token pode operar no ns/db informado? ROOT tudo; NAMESPACE o mesmo ns; DATABASE o mesmo ns e db. */
    public static boolean allows(AxonValue auth, String ns, String db) {
        if (!authenticated(auth)) {
            return true;
        }
        return switch (scope(auth)) {
            case ROOT -> true;
            case NAMESPACE -> ns != null && ns.equals(ns(auth));
            case DATABASE -> ns != null && ns.equals(ns(auth)) && db != null && db.equals(db(auth));
        };
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
