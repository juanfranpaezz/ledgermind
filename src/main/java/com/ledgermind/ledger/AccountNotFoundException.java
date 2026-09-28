package com.ledgermind.ledger;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId);
    }

    public AccountNotFoundException(String reference) {
        super("Account not found: " + reference);
    }
}
