package com.ledgermind.ledger;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long accountId) {
        super("Cuenta no encontrada: " + accountId);
    }

    public AccountNotFoundException(String reference) {
        super("Cuenta no encontrada: " + reference);
    }
}
