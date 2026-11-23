package com.axonbase.server;

import com.axonbase.value.AxonValue;

/** Resposta de uma rota de {@code /admin/v1}: status HTTP e corpo JSON (nulo em 204). */
record AdminV1Response(int status, AxonValue body) {
}
