package com.axonbase.core.engine;

import com.axonbase.common.Messages;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Geohash para o índice espacial hierárquico. Prefixos aninhados permitem usar
 * o varrido ordenado por prefixo do KvBackend para cobrir regiões sem manter a
 * grade fixa de graus.
 */
public final class GeoHash {

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int[] BITS = {16, 8, 4, 2, 1};

    private GeoHash() {
    }

    /** Codifica (lat, lon) até {@code precision} caracteres (máx 12). */
    public static String encode(double lat, double lon, int precision) {
        if (precision < 0 || precision > 12) {
            throw new IllegalArgumentException(Messages.get("geo_precision_invalid"));
        }
        double latMin = -90, latMax = 90, lonMin = -180, lonMax = 180;
        StringBuilder out = new StringBuilder();
        boolean even = true;
        int bit = 0, ch = 0;
        while (out.length() < precision) {
            if (even) {
                double mid = (lonMin + lonMax) / 2;
                if (lon >= mid) {
                    ch |= BITS[bit];
                    lonMin = mid;
                } else {
                    lonMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
                if (lat >= mid) {
                    ch |= BITS[bit];
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            even = !even;
            bit++;
            if (bit == 5) {
                out.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return out.toString();
    }

    /** Centro aproximado da célula codificada. */
    public static double[] decode(String geoHash) {
        double latMin = -90, latMax = 90, lonMin = -180, lonMax = 180;
        boolean even = true;
        for (char c : geoHash.toCharArray()) {
            int index = indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException(Messages.get("geo_hash_invalid", geoHash));
            }
            for (int mask : BITS) {
                if ((index & mask) != 0) {
                    if (even) {
                        lonMin = (lonMin + lonMax) / 2;
                    } else {
                        latMin = (latMin + latMax) / 2;
                    }
                } else if (even) {
                    lonMax = (lonMin + lonMax) / 2;
                } else {
                    latMax = (latMin + latMax) / 2;
                }
                even = !even;
            }
        }
        return new double[]{(latMin + latMax) / 2, (lonMin + lonMax) / 2};
    }

    /**
     * Células de precisão {@code p} que cobrem a caixa [lat0..lat1] x [lon0..lon1].
     * O geohash intercala bits de longitude e latitude (a longitude recebe o bit
     * ímpar extra), por isso as células de lon e lat têm tamanhos distintos:
     * {@code lonCell = 360/2^ceil(5p/2)} e {@code latCell = 180/2^floor(5p/2)}.
     * A varredura amostra cada eixo com o seu próprio passo e começa uma célula
     * antes do canto, cobrindo também as células de borda da caixa. O conjunto
     * resultante é um superconjunto conservador: o WHERE exato filtra depois.
     * Quando o número de células passa do limite, a cobertura desce uma precisão
     * (o prefixo do geohash mantém a busca hierárquica válida).
     */
    public static Set<String> covering(double lat0, double lon0, double lat1, double lon1, int precision) {
        Set<String> out = new LinkedHashSet<>();
        double latStep = cellSizeDegrees(precision);
        double lonStep = lonCellDegrees(precision);
        int lonSteps = (int) Math.ceil((lon1 - lon0) / lonStep) + 2;
        int latSteps = (int) Math.ceil((lat1 - lat0) / latStep) + 2;
        if (lonSteps * latSteps > 256) {
            return covering(lat0, lon0, lat1, lon1, Math.max(0, precision - 1));
        }
        for (int j = 0; j < latSteps; j++) {
            double lat = lat0 - latStep + j * latStep;
            for (int i = 0; i < lonSteps; i++) {
                double lon = lon0 - lonStep + i * lonStep;
                out.add(encode(lat, lon, precision));
            }
        }
        return out;
    }

    /** Tamanho aproximado da célula em latitude (graus) para uma precisão. */
    public static double cellSizeDegrees(int precision) {
        int latBits = precision * 5 / 2;
        if (latBits < 0) latBits = 0;
        return 180.0 / (1L << latBits);
    }

    /** Tamanho aproximado da célula em longitude (graus) para uma precisão. */
    public static double lonCellDegrees(int precision) {
        int lonBits = (precision * 5 + 1) / 2;
        if (lonBits < 0) lonBits = 0;
        return 360.0 / (1L << lonBits);
    }

    private static int indexOf(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
