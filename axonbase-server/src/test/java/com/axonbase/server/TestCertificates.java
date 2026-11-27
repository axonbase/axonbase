package com.axonbase.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Autoridade certificadora descartável dos testes, gerada com o keytool do próprio JDK. */
final class TestCertificates {

    static final String PASSWORD = "senha123";

    /** Chave e certificado emitidos pela autoridade de teste. */
    record Identity(KeyStore keyStore, PrivateKey key, X509Certificate certificate, Path file) {
    }

    private final Path dir;
    private final Path authority;
    private final X509Certificate authorityCertificate;

    TestCertificates(Path dir) throws Exception {
        this.dir = dir;
        this.authority = dir.resolve("ca.p12");
        generate("ca", "CN=Autoridade de Teste", authority, "-ext", "bc:c=ca:true");
        this.authorityCertificate = (X509Certificate) load(authority).getCertificate("ca");
    }

    /** Emite um certificado assinado pela autoridade; {@code extensions} vão para o keytool -gencert. */
    Identity issue(String alias, String dname, String... extensions) throws Exception {
        Path own = dir.resolve(alias + "-own.p12");
        Path request = dir.resolve(alias + ".csr");
        Path signed = dir.resolve(alias + ".cer");
        generate(alias, dname, own);
        keytool("-certreq", "-alias", alias, "-keystore", own.toString(), "-storepass", PASSWORD,
            "-file", request.toString());
        List<String> sign = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", authority.toString(),
            "-storepass", PASSWORD, "-infile", request.toString(), "-outfile", signed.toString(), "-validity", "2"));
        sign.addAll(List.of(extensions));
        keytool(sign.toArray(String[]::new));
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(signed)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        PrivateKey key = (PrivateKey) load(own).getKey(alias, PASSWORD.toCharArray());
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, key, PASSWORD.toCharArray(),
            new Certificate[] {certificate, authorityCertificate});
        Path file = dir.resolve(alias + ".p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        return new Identity(store, key, certificate, file);
    }

    /** JKS contendo só a autoridade. */
    Path truststore() throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        store.load(null, null);
        store.setCertificateEntry("ca", authorityCertificate);
        Path file = dir.resolve("trust.jks");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        return file;
    }

    static String fingerprint(X509Certificate certificate) throws Exception {
        return "SHA256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static KeyStore load(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static void generate(String alias, String dname, Path file, String... extensions) throws Exception {
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "2", "-dname", dname, "-keystore", file.toString(),
            "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD));
        args.addAll(List.of(extensions));
        keytool(args.toArray(String[]::new));
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool falhou: " + output);
        }
    }
}
