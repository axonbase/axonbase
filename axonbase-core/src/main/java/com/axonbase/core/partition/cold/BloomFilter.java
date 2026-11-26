package com.axonbase.core.partition.cold;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Filtro de Bloom das chaves de uma partição fria: responde "com certeza não está" sem baixar o objeto. Usa 16 bits
 * por chave e 11 funções, perto de 0,05% de falsos positivos.
 */
public final class BloomFilter {

    private static final int BITS_PER_KEY = 16;
    private static final int HASHES = 11;

    private final long[] words;

    private BloomFilter(long[] words) {
        this.words = words;
    }

    /** Um filtro vazio dimensionado para {@code keys} chaves. */
    public static BloomFilter sizedFor(int keys) {
        long bits = Math.max(64L, (long) keys * BITS_PER_KEY);
        return new BloomFilter(new long[(int) Math.min(Integer.MAX_VALUE / 8, (bits + 63) / 64)]);
    }

    public void add(String key) {
        long first = hash(key);
        long step = mix(first) | 1L;
        long bits = words.length * 64L;
        for (int i = 0; i < HASHES; i++) {
            long bit = Math.floorMod(first + i * step, bits);
            words[(int) (bit >>> 6)] |= 1L << bit;
        }
    }

    public boolean mightContain(String key) {
        long first = hash(key);
        long step = mix(first) | 1L;
        long bits = words.length * 64L;
        for (int i = 0; i < HASHES; i++) {
            long bit = Math.floorMod(first + i * step, bits);
            if ((words[(int) (bit >>> 6)] & 1L << bit) == 0) {
                return false;
            }
        }
        return true;
    }

    public byte[] encode() {
        ByteBuffer out = ByteBuffer.allocate(words.length * 8);
        for (long word : words) {
            out.putLong(word);
        }
        return out.array();
    }

    public static BloomFilter decode(byte[] raw) {
        if (raw.length == 0 || raw.length % 8 != 0) {
            throw new IllegalArgumentException("filtro de Bloom inválido");
        }
        ByteBuffer in = ByteBuffer.wrap(raw);
        long[] words = new long[raw.length / 8];
        for (int i = 0; i < words.length; i++) {
            words[i] = in.getLong();
        }
        return new BloomFilter(words);
    }

    /** FNV-1a de 64 bits seguido de mistura: estável entre processos e versões. */
    private static long hash(String key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    private static long mix(long value) {
        long h = value;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        return h ^ h >>> 33;
    }
}
