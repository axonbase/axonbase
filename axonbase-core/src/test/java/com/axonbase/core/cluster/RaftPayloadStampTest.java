package com.axonbase.core.cluster;

import com.axonbase.core.hlc.HlcTimestamp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Portão 6 do carimbo: lotes sem carimbo (0.9.x) são lidos e um nó antigo lê entradas novas. */
class RaftPayloadStampTest {

    private static final HlcTimestamp STAMP = new HlcTimestamp(1_790_000_000_123L, 42);

    private static CommittedBatch batch() {
        return new CommittedBatch("tx-1", Map.of("k", "v".getBytes(StandardCharsets.UTF_8)), Set.of("gone"));
    }

    @Test
    void loteComCarimboIdaEVolta() {
        CommittedBatch decoded = RaftPayload.decode(RaftPayload.encode(batch().withStamp(STAMP)));

        assertEquals(STAMP, decoded.stamp());
        assertEquals("tx-1", decoded.transactionId());
        assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), decoded.puts().get("k"));
        assertEquals(Set.of("gone"), decoded.deletes());
    }

    @Test
    void carimboOcupaDozeBytesNoFimEOLoteSemCarimboNaoMudaDeTamanho() {
        byte[] plain = RaftPayload.encode(batch());
        byte[] stamped = RaftPayload.encode(batch().withStamp(STAMP));

        assertEquals(plain.length + 12, stamped.length);
        assertArrayEquals(plain, Arrays.copyOf(stamped, plain.length));
    }

    @Test
    void loteAntigoSemCarimboELidoComoSemCarimbo() {
        CommittedBatch decoded = RaftPayload.decode(RaftPayload.encode(batch()));

        assertEquals(HlcTimestamp.NONE, decoded.stamp());
    }

    @Test
    void loteAindaMaisAntigoSemOIndicadorDeSnapshotContinuaSendoLido() {
        byte[] withFlag = RaftPayload.encode(batch());
        byte[] withoutFlag = Arrays.copyOf(withFlag, withFlag.length - 1);

        CommittedBatch decoded = RaftPayload.decode(withoutFlag);

        assertEquals(true, decoded.fullSnapshot());
        assertEquals(HlcTimestamp.NONE, decoded.stamp());
    }

    @Test
    void noAntigoIgnoraOCarimboEMantemOResto() throws IOException {
        byte[] stamped = RaftPayload.encode(batch().withStamp(STAMP));

        CommittedBatch seenByOldNode = decodeAs0101(stamped);

        assertEquals("tx-1", seenByOldNode.transactionId());
        assertEquals(Set.of("gone"), seenByOldNode.deletes());
        assertEquals(false, seenByOldNode.fullSnapshot());
    }

    @Test
    void carimboCortadoEPayloadInvalido() {
        byte[] stamped = RaftPayload.encode(batch().withStamp(STAMP));

        assertThrows(IllegalArgumentException.class,
            () -> RaftPayload.decode(Arrays.copyOf(stamped, stamped.length - 3)));
    }

    @Test
    void oAppendCarregaOCarimbo() {
        RaftPayload.Append append = RaftPayload.decodeAppend(RaftPayload.append(3, 2, batch().withStamp(STAMP)));

        assertEquals(STAMP, append.batch().stamp());
    }

    @Test
    void osConstrutoresAntigosCriamLoteSemCarimbo() {
        assertEquals(HlcTimestamp.NONE, new CommittedBatch("t", Map.of(), Set.of()).stamp());
        assertEquals(HlcTimestamp.NONE, new CommittedBatch("t", Map.of(), Set.of(), true).stamp());
    }

    /** Cópia fiel do decodificador da 0.10.1: para de ler no indicador de snapshot. */
    private static CommittedBatch decodeAs0101(byte[] raw) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            String tx = in.readUTF();
            int n = in.readInt();
            Map<String, byte[]> puts = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                puts.put(key, in.readNBytes(in.readInt()));
            }
            int count = in.readInt();
            Set<String> deletes = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                deletes.add(in.readUTF());
            }
            boolean full = true;
            if (in.available() > 0) {
                full = in.readBoolean();
            }
            return new CommittedBatch(tx, puts, deletes, full);
        }
    }
}
