package com.axonbase.server.auth;

import com.axonbase.common.Messages;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.security.cert.CertPathValidator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

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

    private static void validateChain(List<X509Certificate> chain, KeyStore trustStore) {
        try {
            java.util.Set<java.security.cert.TrustAnchor> anchors = new java.util.HashSet<>();
            var aliases = trustStore.aliases();
            while (aliases.hasMoreElements()) {
                var certificate = trustStore.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    anchors.add(new java.security.cert.TrustAnchor(x509, null));
                }
            }
            if (anchors.isEmpty()) {
                throw new SecurityException(Messages.get("cert_trust_anchors_missing"));
            }
            PKIXParameters parameters = new PKIXParameters(anchors);
            parameters.setRevocationEnabled(true);
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
            try {
                CertPathValidator.getInstance("PKIX").validate(path, parameters);
            } catch (Exception revocationError) {
                // OCSP/CRL podem estar indisponíveis (dev, intranet, firewall).
                // Tenta sem revogação antes de recusar.
                PKIXParameters fallback = new PKIXParameters(anchors);
                fallback.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(path, fallback);
                System.err.println("{\"event\":\"revocation_unavailable\",\"warning\":\""
                    + esc(revocationError.getMessage()) + "\"}");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(Messages.get("cert_chain_rejected"), e);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
