package com.ledgermind.ledger;

/** Thrown when a transfer loses the concurrency race too many times in a row. */
public class TransferConflictException extends RuntimeException {

    public TransferConflictException(int attempts) {
        super("The transfer could not be applied after " + attempts
                + " attempts due to concurrency contention");
    }
}
