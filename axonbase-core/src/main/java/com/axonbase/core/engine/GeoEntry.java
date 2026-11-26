package com.axonbase.core.engine;

import java.nio.ByteBuffer;

/**
 * Valor das entradas do índice GEO (ver {@link GeoIndex}). Formato versionado: um byte de versão, um byte que diz se
 * há ponto e, havendo, longitude e latitude como doubles. Com as coordenadas na própria entrada,
 * {@code geo::distance(campo, ponto) <= raio} descarta candidatos sem carregar o documento. Geometrias que não são
 * ponto não trazem coordenadas: o leitor então carrega o documento.
 */
final class GeoEntry {

    private static final byte V1 = 1;
    private static final byte NO_POINT = 0;
    private static final byte POINT = 1;
    private static final int POINT_LENGTH = 2 + 16;
    private static final byte[] WITHOUT_POINT = {V1, NO_POINT};

    private GeoEntry() {
    }

    /** {@code point} é {@code {lon, lat}}; {@code null} para geometria que não é ponto. */
    static byte[] encode(double[] point) {
        if (point == null) {
            return WITHOUT_POINT.clone();
        }
        return ByteBuffer.allocate(POINT_LENGTH).put(V1).put(POINT).putDouble(point[0]).putDouble(point[1]).array();
    }

    /** {@code {lon, lat}} da entrada, ou {@code null} quando ela não traz coordenadas. */
    static double[] decode(byte[] value) {
        if (value == null || value.length != POINT_LENGTH || value[0] != V1 || value[1] != POINT) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(value, 2, 16);
        return new double[] {buf.getDouble(), buf.getDouble()};
    }
}
