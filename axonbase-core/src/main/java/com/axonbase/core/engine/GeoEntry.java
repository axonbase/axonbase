package com.axonbase.core.engine;

import java.nio.ByteBuffer;

/**
 * Valor das entradas do índice GEO ({@code SP|ns|db|tabela|índice|célula|chave}). Formato versionado: um byte de
 * versão, um byte que diz se há ponto e, havendo, longitude e latitude como doubles. Com as coordenadas na própria
 * entrada, {@code geo::distance(campo, ponto) <= raio} descarta candidatos sem carregar o documento.
 *
 * <p>Entradas antigas (valor vazio) e geometrias que não são ponto não trazem coordenadas: o leitor então carrega o
 * documento, como antes. O marcador {@link #readyKey} diz que o índice inteiro foi gravado neste formato; sem ele a
 * consulta segue o caminho antigo.</p>
 */
final class GeoEntry {

    private static final byte V1 = 1;
    private static final byte NO_POINT = 0;
    private static final byte POINT = 1;
    private static final int POINT_LENGTH = 2 + 16;
    private static final byte[] WITHOUT_POINT = {V1, NO_POINT};

    private GeoEntry() {
    }

    /** Marcador do índice: todas as entradas gravadas no formato com coordenadas. */
    static String readyKey(String ns, String db, String table, String index) {
        return "SPV|" + ns + "|" + db + "|" + table + "|" + index;
    }

    /**
     * Marcador de que o índice guarda alguma geometria que não é ponto (linha, polígono). Enquanto não existe, toda
     * entrada é um ponto e a consulta pode podar células pela distância; com ele a consulta volta a cobrir as
     * células como o índice antigo, porque a geometria não pontual é candidata pelas células dos seus vértices.
     */
    static String nonPointKey(String ns, String db, String table, String index) {
        return "SPX|" + ns + "|" + db + "|" + table + "|" + index;
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
