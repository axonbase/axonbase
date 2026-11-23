package com.axonbase.core.backup;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backup em streaming (.axbak) e criptografia em blocos. */
class BackupStreamTest {

    private static Datastore novo() {
        return new Datastore(new MemoryBackend());
    }

    private static byte[] dump(Datastore ds, long since) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ds.backupTo(out, since);
        return out.toByteArray();
    }

    private static void restore(Datastore ds, byte[] data) throws IOException {
        ds.restoreCluster(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data),
            StandardCharsets.UTF_8)));
    }

    @Test
    void idaEVoltaComMilharesDeChaves() throws Exception {
        Datastore a = novo();
        for (int i = 0; i < 5000; i++) {
            a.metaPut("k" + i, ("valor-" + i + "\ncom quebra e \t tab").getBytes(StandardCharsets.UTF_8));
        }
        byte[] full = dump(a, 0);
        Datastore b = novo();
        b.metaPut("lixo", new byte[] {1});
        restore(b, full);
        assertEquals(5000, b.metaKeys("k").size());
        assertTrue(b.metaGet("lixo").isEmpty());
        assertArrayEquals("valor-42\ncom quebra e \t tab".getBytes(StandardCharsets.UTF_8), b.metaGet("k42").get());
    }

    @Test
    void incrementalLevaSoOQueMudouEAsDelecoes() throws Exception {
        Datastore a = novo();
        for (int i = 0; i < 100; i++) {
            a.metaPut("k" + i, new byte[] {(byte) i});
        }
        long mark = a.backupVersion();
        byte[] full = dump(a, 0);

        a.metaPut("k5", new byte[] {99});
        a.metaPut("novo", new byte[] {7});
        a.metaDelete("k10");
        byte[] inc = dump(a, mark);
        String text = new String(inc, StandardCharsets.UTF_8);
        assertTrue(text.contains("!meta|k5\t"));
        assertTrue(text.contains("!meta|novo\t"));
        assertTrue(text.contains("!del\t"));
        assertFalse(text.contains("!meta|k6\t"), "chave inalterada não entra no incremental");

        // full + incremental mesclados (como o restore em S3 faz) reproduzem o estado atual
        String merged = new String(full, StandardCharsets.UTF_8)
            + text.substring(text.indexOf('\n') + 1);
        Datastore b = novo();
        restore(b, merged.getBytes(StandardCharsets.UTF_8));
        assertEquals(100, b.metaKeys("").stream().filter(k -> !k.startsWith("backup")).count());
        assertArrayEquals(new byte[] {99}, b.metaGet("k5").get());
        assertArrayEquals(new byte[] {7}, b.metaGet("novo").get());
        assertTrue(b.metaGet("k10").isEmpty());
    }

    @Test
    void restoreRecusaCabecalhoInvalidoSemApagarNada() throws Exception {
        Datastore a = novo();
        a.metaPut("x", new byte[] {1});
        assertThrows(RuntimeException.class, () -> restore(a, "lixo\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(a.metaGet("x").isPresent());
    }

    @Test
    void criptografiaEmBlocosIdaEVolta() throws Exception {
        byte[] plain = new byte[300_000];
        new Random(1).nextBytes(plain);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = StreamEncryption.encrypting(sink, "senha")) {
            out.write(plain, 0, 1000);
            out.write(plain, 1000, plain.length - 1000);
        }
        byte[] enc = sink.toByteArray();
        assertTrue(new String(enc, 0, 40, StandardCharsets.US_ASCII).startsWith(StreamEncryption.HEADER));
        int nl = indexOfNl(enc);
        try (InputStream in = StreamEncryption.decrypting(
                new ByteArrayInputStream(enc, nl + 1, enc.length - nl - 1), "senha")) {
            assertArrayEquals(plain, in.readAllBytes());
        }
        // compatível com o decryptBytes usado pelo restore S3
        assertArrayEquals(plain, S3Encryption.decryptBytes(enc, "senha"));
    }

    @Test
    void senhaErradaTruncamentoEReordenacaoSaoRecusados() throws Exception {
        byte[] plain = new byte[200_000];
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = StreamEncryption.encrypting(sink, "senha")) {
            out.write(plain);
        }
        String[] lines = new String(sink.toByteArray(), StandardCharsets.US_ASCII).split("\n");
        assertTrue(lines.length >= 5, "esperado cabeçalho mais 4 blocos");
        assertThrows(IOException.class, () -> decrypt(lines, "outra", 1, lines.length));
        // trunca o último bloco
        assertThrows(IOException.class, () -> decrypt(lines, "senha", 1, lines.length - 1));
        // troca a ordem de dois blocos
        String[] swapped = Arrays.copyOf(lines, lines.length);
        String t = swapped[1];
        swapped[1] = swapped[2];
        swapped[2] = t;
        assertThrows(IOException.class, () -> decrypt(swapped, "senha", 1, swapped.length));
        // íntegro continua funcionando
        assertEquals(plain.length, decrypt(lines, "senha", 1, lines.length).length);
    }

    private static byte[] decrypt(String[] lines, String pw, int from, int to) throws IOException {
        String body = String.join("\n", Arrays.copyOfRange(lines, from, to)) + "\n";
        try (InputStream in = StreamEncryption.decrypting(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.US_ASCII)), pw)) {
            return in.readAllBytes();
        }
    }

    private static int indexOfNl(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
