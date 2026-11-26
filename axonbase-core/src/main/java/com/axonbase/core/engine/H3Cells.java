package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import com.uber.h3core.util.LatLng;

import java.io.IOException;
import java.util.List;

/**
 * Acesso à biblioteca H3 (índice hexagonal hierárquico) e o caminho de célula usado nas chaves do índice GEO.
 *
 * <p>O identificador de 64 bits do H3 guarda a resolução nos bits altos, então a forma hexadecimal não serve de
 * prefixo. O caminho daqui é a célula base com três dígitos seguida de um dígito por resolução: o caminho de um
 * ancestral é sempre prefixo do caminho dos descendentes, e uma varredura por prefixo lê a subárvore inteira.</p>
 *
 * <p>A hierarquia do H3 é lógica: um filho não cabe exatamente dentro do hexágono do pai. {@link #reachMeters} dá a
 * folga que a cobertura precisa para não perder pontos por causa disso.</p>
 */
final class H3Cells {

    /** Resolução das entradas do índice: arestas de cerca de 10 metros. */
    static final int STORAGE_RESOLUTION = 12;
    static final int PATH_LENGTH = 3 + STORAGE_RESOLUTION;

    /** Quantas arestas médias da resolução cobrem, com folga, o ponto descendente mais distante do centro. */
    private static final double REACH_FACTOR = 2.0;

    private H3Cells() {
    }

    /** Biblioteca nativa carregada na primeira vez que é usada. */
    private static final class Holder {
        private static final H3Core CORE = load();
        private static final double[] REACH = reach(CORE);
        private static final List<Long> BASE = List.copyOf(CORE.getRes0Cells());

        private static H3Core load() {
            try {
                return H3Core.newInstance();
            } catch (IOException | UnsatisfiedLinkError e) {
                throw AxonError.internal(Messages.get("geo_h3_unavailable", e.getMessage()));
            }
        }

        private static double[] reach(H3Core core) {
            double[] out = new double[16];
            for (int r = 0; r < out.length; r++) {
                out[r] = REACH_FACTOR * core.getHexagonEdgeLengthAvg(r, LengthUnit.m);
            }
            return out;
        }
    }

    static H3Core core() {
        return Holder.CORE;
    }

    static List<Long> baseCells() {
        return Holder.BASE;
    }

    static List<Long> children(long cell) {
        return core().cellToChildren(cell, resolution(cell) + 1);
    }

    static int resolution(long cell) {
        return (int) ((cell >>> 52) & 0xF);
    }

    /** Célula que contém o ponto. A longitude pode vir fora de -180..180; a latitude precisa estar em -90..90. */
    static long cellOf(double lon, double lat, int resolution) {
        return core().latLngToCell(lat, wrap(lon), resolution);
    }

    /** Longitude equivalente em -180..180 (a de entrada, quando já está no intervalo). */
    static double wrap(double lon) {
        if (lon >= -180 && lon <= 180) {
            return lon;
        }
        return ((lon + 180) % 360 + 360) % 360 - 180;
    }

    /** Centro da célula como {@code {lon, lat}}. */
    static double[] center(long cell) {
        LatLng at = core().cellToLatLng(cell);
        return new double[] {at.lng, at.lat};
    }

    /** Célula base com três dígitos e um dígito (0 a 6) por resolução até a da célula. */
    static String path(long cell) {
        int resolution = resolution(cell);
        char[] out = new char[3 + resolution];
        int base = (int) ((cell >>> 45) & 0x7F);
        out[0] = (char) ('0' + base / 100);
        out[1] = (char) ('0' + base / 10 % 10);
        out[2] = (char) ('0' + base % 10);
        for (int r = 1; r <= resolution; r++) {
            out[2 + r] = (char) ('0' + ((cell >>> (3 * (15 - r))) & 7));
        }
        return new String(out);
    }

    /** Caminho de armazenamento do ponto. */
    static String pointPath(double lon, double lat) {
        return path(cellOf(lon, lat, STORAGE_RESOLUTION));
    }

    /** Distância máxima, em metros, do centro de uma célula desta resolução a qualquer ponto indexado sob ela. */
    static double reachMeters(int resolution) {
        return Holder.REACH[resolution];
    }
}
