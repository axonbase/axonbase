package com.axonbase.core.control;

/**
 * Codec do material de autenticação que entra no plano de controle.
 *
 * <p>A senha em texto puro nunca chega aqui. O que atravessa o log Raft e o
 * snapshot é o par {@code salt:hash} produzido pelo catálogo de identidades, no
 * formato aceito pela cláusula {@code PASSHASH} do AxonQL. Assim, um seguidor ou
 * um nó que reinicia reconstrói a identidade sem rehashear nada, o que evita o
 * bug clássico de perder a senha no replay.</p>
 */
public final class AuthCodec {

    private AuthCodec() {
    }

    /** Formata salt e hash na forma aceita por {@code PASSHASH}. */
    public static String passhash(String saltHex, String hashHex) {
        if (saltHex == null || saltHex.isBlank() || hashHex == null || hashHex.isBlank()) {
            throw new IllegalArgumentException("salt e hash são obrigatórios no plano de controle");
        }
        return saltHex + ':' + hashHex;
    }

    /** Separa um {@code PASSHASH} em salt e hash. */
    public static Credential parse(String passhash) {
        int split = passhash == null ? -1 : passhash.indexOf(':');
        if (split < 1 || split == passhash.length() - 1) {
            throw new IllegalArgumentException("PASSHASH deve ter o formato salt:hash");
        }
        return new Credential(passhash.substring(0, split), passhash.substring(split + 1));
    }

    public record Credential(String saltHex, String hashHex) {
    }
}
