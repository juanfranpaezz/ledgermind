package com.ledgermind.ledger;

/** Se lanza cuando una transferencia pierde la carrera de concurrencia demasiadas veces seguidas. */
public class TransferConflictException extends RuntimeException {

    public TransferConflictException(int attempts) {
        super("La transferencia no pudo aplicarse tras " + attempts
                + " intentos por contencion de concurrencia");
    }
}
