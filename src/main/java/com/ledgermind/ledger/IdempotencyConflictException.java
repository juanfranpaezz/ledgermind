package com.ledgermind.ledger;

/**
 * Se reuso una {@code idempotency_key} para una operacion DISTINTA (parametros diferentes a la original).
 *
 * <p>Una idempotency-key identifica UNA operacion: reusarla con otro cuerpo es un error del cliente, no un
 * replay. Devolver el asiento original seria enganioso (el cliente creeria que se aplico SU pedido nuevo).
 * Se mapea a 409 Conflict. (Stripe usa 400 con {@code error_type=idempotency_error}; el principio es el mismo:
 * la clave esta ligada a la primera request y no se puede reutilizar para otra.)
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("La idempotency-key '" + idempotencyKey + "' ya fue usada para una operacion distinta");
    }
}
