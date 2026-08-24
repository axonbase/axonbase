package com.axonbase.core;

import com.axonbase.value.AxonValue;

import java.util.HashMap;
import java.util.Map;

/** Variables de sesión dunha consulta (o equivalente ás variables de AxonQL). */
public final class Variables {

    private final Map<String, AxonValue> vars = new HashMap<>();

    public void set(String name, AxonValue value) {
        vars.put(name, value);
    }

    public AxonValue get(String name) {
        return vars.get(name);
    }

    public boolean contains(String name) {
        return vars.containsKey(name);
    }

    public void unset(String name) {
        vars.remove(name);
    }

    public AxonValue getOrMissing(String name) {
        return vars.get(name);
    }

    public Map<String, AxonValue> copy() {
        return new HashMap<>(vars);
    }
}