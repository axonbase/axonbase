package com.axonbase.server.auth;

import com.axonbase.common.Messages;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** In-memory JKS truststores and their certificate identity collection rules. */
public final class TrustStoreRegistry {

    private record RegisteredTrustStore(KeyStore store, String collector, List<String> oids) {
    }

    private final Map<String, RegisteredTrustStore> stores = new ConcurrentHashMap<>();

    /** Loads or replaces a JKS; the password is used only while loading it. */
    public void register(String name, String path, char[] password) {
        register(name, path, password, "NONE", List.of());
    }

    public void register(String name, String path, char[] password, String collector, List<String> oids) {
        if (name == null || name.isBlank() || path == null || path.isBlank()) {
            throw new IllegalArgumentException(Messages.get("cert_store_name_path_required"));
        }
        if (password == null) {
            throw new IllegalArgumentException(Messages.get("cert_store_password_required"));
        }
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            KeyStore store = KeyStore.getInstance("JKS");
            store.load(input, password);
            stores.put(name, new RegisteredTrustStore(store,
                collector == null ? "NONE" : collector.toUpperCase(),
                oids == null ? List.of() : List.copyOf(oids)));
        } catch (Exception e) {
            throw new IllegalArgumentException(Messages.get("cert_store_load_failed", name), e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public KeyStore get(String name) {
        RegisteredTrustStore registered = stores.get(name);
        return registered == null ? null : registered.store();
    }

    /** Collects the certificate identity using the rule configured for this JKS. */
    public String collectUser(String name, java.security.cert.X509Certificate certificate) {
        RegisteredTrustStore registered = stores.get(name);
        if (registered == null || certificate == null) return null;
        if (!"OID".equals(registered.collector())) return null;
        try {
            LdapName subject = new LdapName(certificate.getSubjectX500Principal().getName());
            for (String oid : registered.oids()) {
                for (Rdn rdn : subject.getRdns()) {
                    if (oid.equals(rdn.getType())) {
                        String value = String.valueOf(rdn.getValue()).trim();
                        if (!value.isEmpty()) return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // No identity was collected from the subject DN.
        }
        return null;
    }

    public boolean usesIcpBrasilCollector(String name) {
        RegisteredTrustStore registered = stores.get(name);
        return registered != null && "ICPBRASIL".equals(registered.collector());
    }
}
