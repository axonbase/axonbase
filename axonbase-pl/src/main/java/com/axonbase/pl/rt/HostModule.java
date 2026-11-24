package com.axonbase.pl.rt;

/** Modulo nativo fornecido pelo host (por exemplo db). Os atributos vem de PlHost.attr. */
public final class HostModule {
    public final String nome;

    public HostModule(String nome) {
        this.nome = nome;
    }
}
