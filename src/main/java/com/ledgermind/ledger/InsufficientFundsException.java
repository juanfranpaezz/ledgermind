package com.ledgermind.ledger;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId, long available, long requested) {
        super("Saldo insuficiente en cuenta " + accountId
                + ": disponible " + available + " centavos, pedido " + requested + " centavos");
    }
}
