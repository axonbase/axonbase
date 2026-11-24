package com.axonbase.core.pl;

import com.axonbase.pl.PlDebugHook;
import com.axonbase.pl.PlLimits;

/**
 * Parâmetros de uma execução sob depuração.
 *
 * @param hook gancho composto (depurador, perfilador e registro de linha das consultas)
 * @param profile consultas db.* disparadas
 * @param limits limites compartilhados com o depurador (crédito do tempo parado, cancelamento)
 * @param noDdl proíbe DDL (depuração que será revertida)
 */
record DebugRun(PlDebugHook hook, QueryProfile profile, PlLimits limits, boolean noDdl) {
}
