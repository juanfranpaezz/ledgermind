package com.ledgermind.ledger;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId, long available, long requested) {
        super("Insufficient funds in account " + accountId
                + ": available " + available + " cents, requested " + requested + " cents");
    }
}
