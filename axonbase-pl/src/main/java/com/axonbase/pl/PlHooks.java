package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;

/** Composicao de ganchos de depuracao: todos recebem os eventos, na ordem informada. */
public final class PlHooks {
    private PlHooks() {
    }

    /** Junta os ganchos nao nulos. Devolve null se nao sobrar nenhum e o proprio gancho se sobrar um. */
    public static PlDebugHook compor(PlDebugHook... hooks) {
        List<PlDebugHook> l = java.util.Arrays.stream(hooks).filter(java.util.Objects::nonNull).toList();
        if (l.isEmpty()) {
            return null;
        }
        if (l.size() == 1) {
            return l.get(0);
        }
        return new PlDebugHook() {
            @Override
            public void onLine(PlFrame frame, int line) {
                for (PlDebugHook h : l) {
                    h.onLine(frame, line);
                }
            }

            @Override
            public void onCall(PlFrame frame) {
                for (PlDebugHook h : l) {
                    h.onCall(frame);
                }
            }

            @Override
            public void onReturn(PlFrame frame, AxonValue valor) {
                for (PlDebugHook h : l) {
                    h.onReturn(frame, valor);
                }
            }

            @Override
            public void onException(PlFrame frame, PlError erro) {
                for (PlDebugHook h : l) {
                    h.onException(frame, erro);
                }
            }

            @Override
            public void onHostCall(PlFrame frame, String modulo, String nome, List<AxonValue> args,
                                   Map<String, AxonValue> kwargs) {
                for (PlDebugHook h : l) {
                    h.onHostCall(frame, modulo, nome, args, kwargs);
                }
            }
        };
    }
}
