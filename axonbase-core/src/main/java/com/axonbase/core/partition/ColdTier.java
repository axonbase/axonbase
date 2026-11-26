package com.axonbase.core.partition;

/** A camada fria de um motor: por enquanto, só o aviso de que ela não está configurada. */
public interface ColdTier {

    /** Há um armazenamento de objetos configurado. */
    boolean configured();

    /** Leitura dos registros frios. */
    ColdRecords records();

    ColdTier NONE = new ColdTier() {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public ColdRecords records() {
            return ColdRecords.UNCONFIGURED;
        }
    };
}
