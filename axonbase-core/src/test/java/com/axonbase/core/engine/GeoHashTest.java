package com.axonbase.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoHashTest {

    @Test
    void encodeDecodeDevolveCentroDasCellas() {
        String h1 = GeoHash.encode(-23.55, -46.63, 6);
        double[] center = GeoHash.decode(h1);
        String h2 = GeoHash.encode(center[0], center[1], 6);
        assertTrue(h1.equals(h2), "centro deve re-encodar na mesma célula: " + h1 + " vs " + h2);
    }

    @Test
    void prefixoDeMenorPrecisaoCasaComCeluLaDeMaior() {
        String coarse = GeoHash.encode(-23.5, -46.6, 4);
        String fine = GeoHash.encode(-23.55, -46.63, 8);
        assertTrue(fine.startsWith(coarse), fine + " deve começar com " + coarse);
    }

    @Test
    void coveringGeraCellPdadaVezesCobrindoAcaixa() {
        var cells = GeoHash.covering(-1.0, 0.0, 1.0, 1.0, 4);
        assertTrue(!cells.isEmpty());
        assertTrue(cells.size() <= 256, "cobertura deve ser limitada: " + cells.size());
    }
}