package com.axonbase.server.auth;

import com.axonbase.common.Messages;
import com.axonbase.server.AxonLog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Verifica prova de posse de certificados A1/A3 sem receber chave privada ou PIN. */
public final class CertificateChallengeService {

    public record Challenge(String id, String value, Instant expiresAt) {
    }

    public record VerifiedCertificate(String fingerprint, X509Certificate certificate) {
    }

    private record Pending(String value, Instant expiresAt, String store, String namespace, String database) {
    }

    /** Segundos de espera para conectar e para ler cada consulta; o padrão do JDK é 15. */
    private static final String REVOCATION_TIMEOUT_SECONDS = "5";

    static {
        // o JDK lê estas propriedades uma vez, na primeira consulta; o que o operador definir com -D prevalece
        for (String property : List.of("com.sun.security.ocsp.timeout", "com.sun.security.ocsp.readtimeout",
            "com.sun.security.crl.timeout", "com.sun.security.crl.readtimeout")) {
            if (System.getProperty(property) == null) {
                System.setProperty(property, REVOCATION_TIMEOUT_SECONDS);
            }
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final boolean allowUnknownRevocation;

    /** Estrito: sem conseguir verificar a revogação, o certificado é recusado. */
    public CertificateChallengeService() {
        this(false);
    }

    /**
     * @param allowUnknownRevocation aceita, com aviso no log, o certificado cuja revogação não pôde ser verificada.
     *                               Certificado revogado e as demais falhas de validação são sempre recusados.
     */
    public CertificateChallengeService(boolean allowUnknownRevocation) {
        this.allowUnknownRevocation = allowUnknownRevocation;
    }

    public Challenge begin(String store, String namespace, String database) {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String id = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(30);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        pending.put(id, new Pending(value, expiresAt, store, namespace, database));
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return new Challenge(id, value, expiresAt);
    }

    /**
     * Valida a cadeia no truststore e retorna o fingerprint. Não exige desafio
     * porque o mTLS já autenticou o peer no nível do transporte.
     */
    public VerifiedCertificate verifyChain(List<X509Certificate> chain, KeyStore trustStore) {
        validateChain(chain, trustStore);
        return new VerifiedCertificate(fingerprint(chain.getFirst()), chain.getFirst());
    }

    /**
     * Valida a cadeia no truststore e a assinatura do desafio. A assinatura deve
     * cobrir o valor retornado por {@link #begin(String, String, String)}.
     */
    public VerifiedCertificate complete(Challenge challenge, String store, String namespace, String database,
                                        List<String> encodedChain, String encodedSignature,
                                        KeyStore trustStore) {
        Pending expected = pending.remove(challenge.id());
        if (expected == null || expected.expiresAt().isBefore(Instant.now())
            || !expected.value().equals(challenge.value()) || !expected.store().equals(store)
            || !java.util.Objects.equals(expected.namespace(), namespace)
            || !java.util.Objects.equals(expected.database(), database)) {
            throw new SecurityException(Messages.get("cert_challenge_invalid"));
        }
        List<X509Certificate> chain = parseChain(encodedChain);
        validateChain(chain, trustStore);
        verifySignature(chain.getFirst(), expected.value(), encodedSignature);
        return new VerifiedCertificate(fingerprint(chain.getFirst()), chain.getFirst());
    }

    private static List<X509Certificate> parseChain(List<String> encodedChain) {
        if (encodedChain == null || encodedChain.isEmpty()) {
            throw new SecurityException(Messages.get("cert_chain_missing"));
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return encodedChain.stream().map(value -> {
                try {
                    return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(
                        Base64.getDecoder().decode(value)));
                } catch (Exception e) {
                    throw new SecurityException(Messages.get("cert_x509_invalid"), e);
                }
            }).toList();
        } catch (java.security.cert.CertificateException e) {
            throw new IllegalStateException(Messages.get("cert_x509_unavailable"), e);
        }
    }

    /**
     * Duas etapas: primeiro cadeia, assinaturas, validade e restrições, sem revogação; depois a revogação de cada
     * certificado, um a um. Assim uma falha de consulta nunca encobre a revogação de outro certificado da cadeia,
     * e só se consultam os endereços de certificados assinados por uma autoridade confiável.
     */
    private void validateChain(List<X509Certificate> chain, KeyStore trustStore) {
        try {
            Set<X509Certificate> trusted = trustedCertificates(trustStore);
            if (trusted.isEmpty()) {
                throw new SecurityException(Messages.get("cert_trust_anchors_missing"));
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate certificate : trusted) {
                anchors.add(new TrustAnchor(certificate, null));
            }
            PKIXParameters parameters = new PKIXParameters(anchors);
            parameters.setRevocationEnabled(false);
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
            checkRevocation(chain, trusted);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(Messages.get("cert_chain_rejected"), e);
        }
    }

    private static Set<X509Certificate> trustedCertificates(KeyStore trustStore) throws KeyStoreException {
        Set<X509Certificate> trusted = new HashSet<>();
        var aliases = trustStore.aliases();
        while (aliases.hasMoreElements()) {
            if (trustStore.getCertificate(aliases.nextElement()) instanceof X509Certificate x509) {
                trusted.add(x509);
            }
        }
        return trusted;
    }

    /**
     * Sobe do certificado do titular até uma raiz confiável. Abaixo da primeira autoridade do truststore o emissor é o
     * seguinte da cadeia recebida; dela para cima, só vale o que está no truststore. Uma autoridade do truststore cujo
     * emissor não está nele é aceita como âncora, sem consulta.
     */
    private void checkRevocation(List<X509Certificate> chain, Set<X509Certificate> trusted)
        throws GeneralSecurityException {
        Set<X509Certificate> visited = new HashSet<>();
        X509Certificate current = chain.getFirst();
        boolean reachedTrust = false;
        for (int next = 1; visited.add(current) && !isTrustedRoot(current, trusted); next++) {
            reachedTrust |= trusted.contains(current);
            X509Certificate issuer = !reachedTrust && next < chain.size()
                ? chain.get(next) : trustedIssuer(current, trusted);
            if (issuer == null) {
                if (reachedTrust) {
                    return;
                }
                throw new SecurityException(Messages.get("cert_chain_rejected"));
            }
            requireNotRevoked(current, issuer);
            current = issuer;
        }
    }

    private static boolean isTrustedRoot(X509Certificate certificate, Set<X509Certificate> trusted) {
        return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
            && trusted.stream().anyMatch(anchor ->
                anchor.getSubjectX500Principal().equals(certificate.getSubjectX500Principal())
                    && anchor.getPublicKey().equals(certificate.getPublicKey()));
    }

    private static X509Certificate trustedIssuer(X509Certificate certificate, Set<X509Certificate> trusted) {
        for (X509Certificate candidate : trusted) {
            if (candidate.equals(certificate)
                || !candidate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                continue;
            }
            try {
                certificate.verify(candidate.getPublicKey());
                return candidate;
            } catch (GeneralSecurityException e) {
                // mesmo nome, outra chave: não é o emissor
            }
        }
        return null;
    }

    /**
     * Consulta o OCSP e, se ele não responder que o certificado está regular nem que está revogado, a lista de
     * revogação. Cada fonte é consultada em separado para o motivo de cada falha não se misturar.
     */
    private void requireNotRevoked(X509Certificate certificate, X509Certificate issuer)
        throws GeneralSecurityException {
        CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(certificate));
        CertPathValidatorException ocsp = query(path, issuer,
            EnumSet.of(PKIXRevocationChecker.Option.NO_FALLBACK));
        if (ocsp == null) {
            return;
        }
        if (isRevoked(ocsp)) {
            throw revoked(certificate, ocsp);
        }
        CertPathValidatorException crl = query(path, issuer, EnumSet.of(
            PKIXRevocationChecker.Option.NO_FALLBACK, PKIXRevocationChecker.Option.PREFER_CRLS));
        if (crl == null) {
            return;
        }
        if (isRevoked(crl)) {
            throw revoked(certificate, crl);
        }
        if (!isUnavailable(crl)) {
            throw new SecurityException(Messages.get("cert_chain_rejected"), crl);
        }
        Map<String, Object> details = Map.of(
            "subject", certificate.getSubjectX500Principal().getName(),
            "serial", certificate.getSerialNumber().toString(16),
            "accepted", allowUnknownRevocation,
            "error", "OCSP: " + ocsp.getMessage() + "; CRL: " + crl.getMessage());
        AxonLog.log("warn", "revocation_unavailable", "revogação do certificado não verificada", details);
        if (!allowUnknownRevocation) {
            throw new SecurityException(Messages.get("cert_revocation_unavailable"), crl);
        }
    }

    /** Devolve {@code null} quando a fonte confirma o certificado, ou o motivo da falha. */
    private static CertPathValidatorException query(CertPath path, X509Certificate issuer,
                                                    Set<PKIXRevocationChecker.Option> options)
        throws GeneralSecurityException {
        CertPathValidator validator = CertPathValidator.getInstance("PKIX");
        // o verificador obtido assim consulta OCSP e os pontos de distribuição da lista sem depender das
        // propriedades ocsp.enable e com.sun.security.enableCRLDP, desligadas por padrão no JDK
        PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
        checker.setOptions(options);
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
        parameters.addCertPathChecker(checker);
        try {
            validator.validate(path, parameters);
            return null;
        } catch (CertPathValidatorException e) {
            return e;
        }
    }

    private static boolean isRevoked(CertPathValidatorException e) {
        return e.getReason() == CertPathValidatorException.BasicReason.REVOKED;
    }

    /** Lista não obtida ou ilegível. Qualquer outro motivo é recusa, com ou sem a opção. */
    private static boolean isUnavailable(CertPathValidatorException e) {
        return e.getReason() == CertPathValidatorException.BasicReason.UNDETERMINED_REVOCATION_STATUS
            || e.getCause() instanceof CertStoreException;
    }

    private static SecurityException revoked(X509Certificate certificate, CertPathValidatorException cause) {
        AxonLog.log("warn", "certificate_revoked", "certificado revogado recusado", Map.of(
            "subject", certificate.getSubjectX500Principal().getName(),
            "serial", certificate.getSerialNumber().toString(16)));
        return new SecurityException(Messages.get("cert_revoked"), cause);
    }

    private static void verifySignature(X509Certificate certificate, String challenge, String encodedSignature) {
        try {
            String algorithm = switch (certificate.getPublicKey().getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> throw new SecurityException(Messages.get("cert_algorithm_unsupported"));
            };
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(certificate.getPublicKey());
            signature.update(challenge.getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getDecoder().decode(encodedSignature))) {
                throw new SecurityException(Messages.get("cert_signature_invalid"));
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(Messages.get("cert_signature_verify_failed"), e);
        }
    }

    private static String fingerprint(X509Certificate certificate) {
        try {
            return "SHA256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("cert_fingerprint_failed"), e);
        }
    }
}
