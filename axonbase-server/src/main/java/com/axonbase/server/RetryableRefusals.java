package com.axonbase.server;

import com.axonbase.common.AxonError;
import com.axonbase.core.qos.QosCodes;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Respostas HTTP de recusas em que nada foi executado, para o cliente saber que pode repetir. */
final class RetryableRefusals {

    private RetryableRefusals() {
    }

    /** Recusa do QoS: {@code 503} com {@code Retry-After}. Devolve {@code false} para qualquer outra falha. */
    static boolean overloaded(HttpServletResponse resp, Throwable failure) throws IOException {
        if (!(failure instanceof AxonError error) || error.code() != QosCodes.REJECTED) {
            return false;
        }
        resp.setStatus(503);
        resp.setHeader("Retry-After", "1");
        resp.setContentType("application/json");
        resp.getOutputStream().write(("{\"status\":\"ERR\",\"kind\":\"QOS_REJECTED\",\"detail\":"
            + AxonJson.write(AxonValue.str(error.getMessage())) + "}").getBytes(StandardCharsets.UTF_8));
        return true;
    }
}
