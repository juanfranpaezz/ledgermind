package com.ledgermind.ledger;

/** An order to transfer {@code amount} cents from one account to another, idempotently. */
public record TransferCommand(
        Long debitAccountId,
        Long creditAccountId,
        long amount,
        String idempotencyKey) {
}
