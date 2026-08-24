package com.axonbase.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AxonValue: tipos e orde total")
class AxonValueTest {

    @Test
    void tiposPrimitivos() {
        assertEquals(AxonValue.none().type(), AxonValue.AxonType.NONE);
        assertEquals(AxonValue.nul().type(), AxonValue.AxonType.NULL);
        assertEquals(AxonValue.bool(true).type(), AxonValue.AxonType.BOOL);
        assertEquals(AxonValue.str("ola").asString(), "ola");
        assertEquals(AxonValue.num(42L).asLong(), 42L);
        assertEquals(AxonValue.num(3.5).asDouble(), 3.5);
    }

    @Test
    void numeroEnteiroIgualADecimal() {
        AxonValue ent = AxonValue.num(10L);
        AxonValue dec = AxonValue.num(new BigDecimal("10.0"));
        assertEquals(ent, dec);
        assertNotEquals(ent, AxonValue.num(11L));
    }

    @Test
    void ordeTotalPorTipo() {
        assertTrue(AxonValue.none().compareTo(AxonValue.nul()) < 0);
        assertTrue(AxonValue.nul().compareTo(AxonValue.bool(false)) < 0);
        assertTrue(AxonValue.bool(false).compareTo(AxonValue.num(1)) < 0);
        assertTrue(AxonValue.num(1).compareTo(AxonValue.str("a")) < 0);
    }

    @Test
    void ordeEntreTipoIgual() {
        assertTrue(AxonValue.num(3).compareTo(AxonValue.num(10)) < 0);
        assertEquals(0, AxonValue.num(3.0).compareTo(AxonValue.num(3)));
    }

    @Test
    void arraysComparanSequencial() {
        AxonValue a = AxonValue.array(List.of(AxonValue.num(1), AxonValue.num(2)));
        AxonValue b = AxonValue.array(List.of(AxonValue.num(1), AxonValue.num(3)));
        assertTrue(a.compareTo(b) < 0);
    }

    @Test
    void obxectoIgual() {
        AxonValue obj1 = AxonValue.object(Map.of("x", AxonValue.num(1)));
        AxonValue obj2 = AxonValue.object(Map.of("x", AxonValue.num(1)));
        assertEquals(obj1, obj2);
    }

    @Test
    void recordIdToString() {
        AxonValue r = AxonValue.record("person", 1L);
        assertEquals("person:1", r.toString());
        assertEquals(AxonValue.AxonType.RECORD_ID, r.type());
    }
}