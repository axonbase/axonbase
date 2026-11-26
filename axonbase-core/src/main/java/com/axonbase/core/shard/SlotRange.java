package com.axonbase.core.shard;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Faixa de slots, com as duas pontas incluídas. */
public record SlotRange(int from, int to) {

    public SlotRange {
        if (from < 0 || to < from || to >= SlotHash.SLOTS) {
            throw new IllegalArgumentException("faixa de slots inválida: " + from + "-" + to);
        }
    }

    public int size() {
        return to - from + 1;
    }

    public boolean contains(int slot) {
        return slot >= from && slot <= to;
    }

    /** Texto como {@code 0-10,20-20}, usado no filtro que o shard recebe. */
    public static String format(List<SlotRange> ranges) {
        return ranges.stream().map(r -> r.from + "-" + r.to).collect(Collectors.joining(","));
    }

    public static List<SlotRange> parse(String text) {
        List<SlotRange> ranges = new ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int dash = part.indexOf('-');
            if (dash < 0) {
                int slot = Integer.parseInt(part.trim());
                ranges.add(new SlotRange(slot, slot));
            } else {
                ranges.add(new SlotRange(Integer.parseInt(part.substring(0, dash).trim()),
                    Integer.parseInt(part.substring(dash + 1).trim())));
            }
        }
        return List.copyOf(ranges);
    }
}
