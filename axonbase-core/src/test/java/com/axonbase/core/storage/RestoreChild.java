package com.axonbase.core.storage;

import java.nio.file.Path;

/**
 * Restaura um backup num processo próprio, para o teste que o mata no meio. Argumentos: diretório de backup,
 * diretório de dados e o atraso, em milissegundos, depois de cada arquivo movido na troca (alarga a janela em que a
 * queda cai no meio da troca). Imprime {@code RESTORE start} e {@code RESTORE done}.
 */
public final class RestoreChild {

    private RestoreChild() {
    }

    public static void main(String[] args) {
        Path backups = Path.of(args[0]);
        Path data = Path.of(args[1]);
        long slow = Long.parseLong(args[2]);
        DirectorySwap.afterMove = () -> {
            try {
                Thread.sleep(slow);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        System.out.println("RESTORE start");
        System.out.flush();
        int latest;
        try (PhysicalBackups engine = PhysicalBackups.open(backups)) {
            latest = engine.list().getLast().id();
        }
        PhysicalBackups.restoreInPlace(backups, latest, data);
        System.out.println("RESTORE done");
        System.out.flush();
    }
}
