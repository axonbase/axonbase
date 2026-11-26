package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.value.AxonValue;
import com.uber.h3core.util.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Funções AxonQL sobre células H3 ({@code geo::h3*}). A célula é a string hexadecimal usada por todas as bibliotecas
 * H3, então o valor troca de sistema sem conversão. Entrada inválida devolve nulo, como as demais funções
 * {@code geo::}; só um pedido que geraria células demais é erro.
 */
final class H3Functions {

    private static final int MAX_RESOLUTION = 15;
    /** Filhos por chamada: até cinco resoluções abaixo (7^5). */
    private static final long MAX_CHILDREN = 16_807;
    /** Anéis por chamada: 30.301 células. */
    private static final int MAX_DISK = 100;

    private H3Functions() {
    }

    /** {@code geo::h3(ponto, resolução)}: célula que contém o ponto. */
    static AxonValue cell(AxonValue geometry, AxonValue resolution) {
        double[] point = GeoIndex.pointOf(geometry);
        int r = whole(resolution);
        if (!GeoIndex.isLocatable(point) || r < 0 || r > MAX_RESOLUTION) {
            return AxonValue.nul();
        }
        return text(H3Cells.cellOf(point[0], point[1], r));
    }

    static AxonValue center(AxonValue cell) {
        Long id = parse(cell);
        if (id == null) {
            return AxonValue.nul();
        }
        double[] at = H3Cells.center(id);
        return GeoVector.point(AxonValue.num(at[0]), AxonValue.num(at[1]));
    }

    /**
     * Contorno como Polygon GeoJSON fechado. Células que cruzam o meridiano de 180 graus saem com longitudes dos dois
     * lados dele, como o H3 as devolve.
     */
    static AxonValue boundary(AxonValue cell) {
        Long id = parse(cell);
        if (id == null) {
            return AxonValue.nul();
        }
        List<AxonValue> ring = new ArrayList<>();
        for (LatLng vertex : H3Cells.core().cellToBoundary(id.longValue())) {
            ring.add(AxonValue.array(List.of(AxonValue.num(vertex.lng), AxonValue.num(vertex.lat))));
        }
        ring.add(ring.get(0));
        return GeoVector.polygon(AxonValue.array(List.of(AxonValue.array(ring))));
    }

    static AxonValue resolution(AxonValue cell) {
        Long id = parse(cell);
        return id == null ? AxonValue.nul() : AxonValue.num(H3Cells.resolution(id));
    }

    static AxonValue parent(AxonValue cell, AxonValue resolution) {
        Long id = parse(cell);
        int r = whole(resolution);
        if (id == null || r < 0 || r > H3Cells.resolution(id)) {
            return AxonValue.nul();
        }
        return text(H3Cells.core().cellToParent(id.longValue(), r));
    }

    static AxonValue children(AxonValue cell, AxonValue resolution) {
        Long id = parse(cell);
        int r = whole(resolution);
        if (id == null || r < H3Cells.resolution(id) || r > MAX_RESOLUTION) {
            return AxonValue.nul();
        }
        if (Math.pow(7, r - H3Cells.resolution(id)) > MAX_CHILDREN) {
            throw AxonError.internal(Messages.get("geo_h3_too_many_cells", "geo::h3::children", MAX_CHILDREN));
        }
        return list(H3Cells.core().cellToChildren(id.longValue(), r));
    }

    /** {@code geo::h3::disk(célula, k)}: a célula e as que estão a até {@code k} passos dela. */
    static AxonValue disk(AxonValue cell, AxonValue rings) {
        Long id = parse(cell);
        int k = whole(rings);
        if (id == null || k < 0) {
            return AxonValue.nul();
        }
        if (k > MAX_DISK) {
            throw AxonError.internal(Messages.get("geo_h3_too_many_cells", "geo::h3::disk", MAX_DISK));
        }
        return list(H3Cells.core().gridDisk(id.longValue(), k));
    }

    private static AxonValue text(long cell) {
        return AxonValue.str(H3Cells.core().h3ToString(cell));
    }

    private static AxonValue list(List<Long> cells) {
        return AxonValue.array(cells.stream().map(H3Functions::text).toList());
    }

    /** Célula válida a partir da string hexadecimal, ou {@code null}. */
    private static Long parse(AxonValue cell) {
        if (cell == null || !cell.isString() || !cell.asString().matches("[0-9a-fA-F]{1,16}")) {
            return null;
        }
        long id = Long.parseUnsignedLong(cell.asString(), 16);
        return H3Cells.core().isValidCell(id) ? id : null;
    }

    /** Inteiro pequeno e exato, ou -1. */
    private static int whole(AxonValue value) {
        if (value == null || !value.isNumber()) {
            return -1;
        }
        double d = value.asDouble();
        return d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d) ? (int) d : -1;
    }
}
