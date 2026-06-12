package com.ledgermind.ledger;

/** Orden de transferir {@code amount} centavos de una cuenta a otra, de forma idempotente. */
public record TransferCommand(
        Long debitAccountId,
        Long creditAccountId,
        long amount,
        String idempotencyKey) {
}
