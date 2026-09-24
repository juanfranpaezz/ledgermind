package com.ledgermind.ledger;

/**
 * La cuenta tiene una marca de sobregiro ACTIVA: el barrido re-derivo su saldo desde el journal y viola su regla de
 * sobregiro. No acepta transferencias (ni como origen ni como destino) hasta que un operador la descongele.
 */
public class AccountFrozenException extends RuntimeException {

    private final long accountId;
    private final long flagId;

    public AccountFrozenException(long accountId, long flagId) {
        super("Cuenta " + accountId + " CONGELADA por sobregiro detectado (marca #" + flagId + "): el barrido"
                + " re-derivo su saldo desde el journal y viola su regla de sobregiro. No acepta transferencias hasta"
                + " que un operador la descongele (unfreeze_account, queda registrado quien y por que).");
        this.accountId = accountId;
        this.flagId = flagId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getFlagId() {
        return flagId;
    }
}
