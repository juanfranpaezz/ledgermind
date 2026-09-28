package com.ledgermind.ledger;

/**
 * An {@code idempotency_key} was reused for a DIFFERENT operation (parameters different from the original).
 *
 * <p>An idempotency key identifies ONE operation: reusing it with another body is a client error, not a
 * replay. Returning the original posting would be misleading (the client would believe ITS new request was applied).
 * It maps to 409 Conflict. (Stripe uses 400 with {@code error_type=idempotency_error}; the principle is the same:
 * the key is bound to the first request and cannot be reused for another.)
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("The idempotency-key '" + idempotencyKey + "' was already used for a different operation");
    }
}
