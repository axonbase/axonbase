package com.axonbase.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Valor das entradas do índice GEO")
class GeoEntryTest {

    @Test
    @DisplayName("o ponto volta idêntico")
    void roundTrip() {
        double[] point = {-46.633308, -23.55052};
        assertArrayEquals(point, GeoEntry.decode(GeoEntry.encode(point)), 0);
    }

    @Test
    @DisplayName("sem ponto, vazio, truncado, de outra versão ou de outro tipo não tem coordenadas")
    void withoutCoordinates() {
        assertNull(GeoEntry.decode(GeoEntry.encode(null)));
        assertNull(GeoEntry.decode(null));
        assertNull(GeoEntry.decode(new byte[0]));
        byte[] otherVersion = GeoEntry.encode(new double[] {1, 2});
        otherVersion[0] = 9;
        assertNull(GeoEntry.decode(otherVersion));
        byte[] otherKind = GeoEntry.encode(new double[] {1, 2});
        otherKind[1] = 0;
        assertNull(GeoEntry.decode(otherKind));
    }
}
