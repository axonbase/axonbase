package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;

/** Funcao fornecida pelo host ao programa (por exemplo db.query). So recebe e devolve AxonValue. */
@FunctionalInterface
public interface PlCallable {
    AxonValue call(List<AxonValue> args, Map<String, AxonValue> kwargs);
}
