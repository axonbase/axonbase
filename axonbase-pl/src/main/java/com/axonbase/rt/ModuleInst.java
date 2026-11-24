package com.axonbase.pl.rt;

import com.axonbase.pl.PlProgram;

/** Instancia carregada de um programa: o espaco de variaveis globais. */
public final class ModuleInst {
    public final PlProgram program;
    public final Object[] globals;
    public final String programa;

    public ModuleInst(PlProgram program) {
        this.program = program;
        this.globals = new Object[program.numGlobais()];
        this.programa = program.nome();
    }
}
