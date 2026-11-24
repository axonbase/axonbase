package com.axonbase.pl.rt;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tipos embutidos e hierarquia de excecoes. */
public final class Types {
    private Types() {
    }

    public static final PyType NONE_T = new PyType("NoneType", null, false, o -> o instanceof NoneType);
    public static final PyType BOOL = new PyType("bool", null, false, o -> o instanceof Boolean);
    public static final PyType INT = new PyType("int", null, false, o -> o instanceof Long || o instanceof Boolean);
    public static final PyType FLOAT = new PyType("float", null, false, o -> o instanceof Double);
    public static final PyType STR = new PyType("str", null, false, o -> o instanceof String);
    public static final PyType LIST = new PyType("list", null, false, o -> o instanceof PyList);
    public static final PyType TUPLE = new PyType("tuple", null, false, o -> o instanceof PyTuple);
    public static final PyType DICT = new PyType("dict", null, false, o -> o instanceof PyDict);
    public static final PyType SET = new PyType("set", null, false, o -> o instanceof PySet s && !s.frozen);
    public static final PyType FROZENSET = new PyType("frozenset", null, false, o -> o instanceof PySet s && s.frozen);
    public static final PyType DECIMAL = new PyType("Decimal", null, false, o -> o instanceof BigDecimal);
    public static final PyType BYTES = new PyType("bytes", null, false, o -> o instanceof PyBytes);
    public static final PyType RANGE = new PyType("range", null, false, o -> o instanceof PyRange);
    public static final PyType FUNCTION = new PyType("function", null, false, o -> o instanceof PyCallable);
    public static final PyType DATETIME = new PyType("datetime", null, false, o -> o instanceof Instant);
    public static final PyType DURATION = new PyType("duration", null, false, o -> o instanceof AxonValue.Duration);
    public static final PyType RECORD = new PyType("record", null, false, o -> o instanceof AxonValue.RecordId);
    public static final PyType UUID_T = new PyType("uuid", null, false, o -> o instanceof UUID);
    public static final PyType ITERATOR = new PyType("iterator", null, false, o -> o instanceof PyIterator);
    public static final PyType MODULE = new PyType("module", null, false, o -> o instanceof PyModule);
    public static final PyType SLICE = new PyType("slice", null, false, o -> o instanceof PySlice);
    public static final PyType OPAQUE = new PyType("axonvalue", null, false, o -> o instanceof AxonValue);

    private static PyType e(String n, PyType p) {
        return new PyType(n, p, true, null);
    }

    public static final PyType BASE_EXCEPTION = e("BaseException", null);
    public static final PyType EXCEPTION = e("Exception", BASE_EXCEPTION);
    public static final PyType ARITHMETIC = e("ArithmeticError", EXCEPTION);
    public static final PyType ZERO_DIV = e("ZeroDivisionError", ARITHMETIC);
    public static final PyType OVERFLOW = e("OverflowError", ARITHMETIC);
    public static final PyType LOOKUP = e("LookupError", EXCEPTION);
    public static final PyType KEY_ERROR = e("KeyError", LOOKUP);
    public static final PyType INDEX_ERROR = e("IndexError", LOOKUP);
    public static final PyType VALUE_ERROR = e("ValueError", EXCEPTION);
    public static final PyType TYPE_ERROR = e("TypeError", EXCEPTION);
    public static final PyType RUNTIME_ERROR = e("RuntimeError", EXCEPTION);
    public static final PyType NOT_IMPL = e("NotImplementedError", RUNTIME_ERROR);
    public static final PyType ATTRIBUTE_ERROR = e("AttributeError", EXCEPTION);
    public static final PyType NAME_ERROR = e("NameError", EXCEPTION);
    public static final PyType UNBOUND = e("UnboundLocalError", NAME_ERROR);
    public static final PyType ASSERTION = e("AssertionError", EXCEPTION);
    public static final PyType STOP_ITER = e("StopIteration", EXCEPTION);
    public static final PyType IMPORT_ERROR = e("ImportError", EXCEPTION);
    public static final PyType MODULE_NOT_FOUND = e("ModuleNotFoundError", IMPORT_ERROR);

    /** Nome do tipo de um valor, como o Python mostraria. */
    public static String nomeDe(Object o) {
        return tipoDe(o).name;
    }

    /** Tipo de um valor. */
    public static PyType tipoDe(Object o) {
        if (o instanceof Long) {
            return INT;
        }
        if (o instanceof String) {
            return STR;
        }
        if (o instanceof Double) {
            return FLOAT;
        }
        if (o instanceof Boolean) {
            return BOOL;
        }
        if (o instanceof NoneType) {
            return NONE_T;
        }
        if (o instanceof PyList) {
            return LIST;
        }
        if (o instanceof PyDict) {
            return DICT;
        }
        if (o instanceof PyTuple) {
            return TUPLE;
        }
        if (o instanceof PySet s) {
            return s.frozen ? FROZENSET : SET;
        }
        if (o instanceof BigDecimal) {
            return DECIMAL;
        }
        if (o instanceof PyException x) {
            return x.type;
        }
        if (o instanceof PyRange) {
            return RANGE;
        }
        if (o instanceof PyBytes) {
            return BYTES;
        }
        if (o instanceof PyCallable) {
            return FUNCTION;
        }
        if (o instanceof Instant) {
            return DATETIME;
        }
        if (o instanceof AxonValue.Duration) {
            return DURATION;
        }
        if (o instanceof AxonValue.RecordId) {
            return RECORD;
        }
        if (o instanceof UUID) {
            return UUID_T;
        }
        if (o instanceof PyIterator) {
            return ITERATOR;
        }
        if (o instanceof PyModule || o instanceof HostModule) {
            return MODULE;
        }
        if (o instanceof PySlice) {
            return SLICE;
        }
        if (o instanceof PyType) {
            return FUNCTION;
        }
        return OPAQUE;
    }
}
