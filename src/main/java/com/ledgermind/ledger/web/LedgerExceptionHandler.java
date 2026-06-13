package com.ledgermind.ledger.web;

import com.ledgermind.ledger.AccountNotFoundException;
import com.ledgermind.ledger.IdempotencyConflictException;
import com.ledgermind.ledger.InsufficientFundsException;
import com.ledgermind.ledger.TransferConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones de dominio a respuestas HTTP estandar (RFC 7807 ProblemDetail).
 * Centraliza el manejo de errores: los controllers no necesitan try/catch.
 */
@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleNotFound(AccountNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(TransferConflictException.class)
    ProblemDetail handleConflict(TransferConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Red para CUALQUIER violacion de integridad de datos que escape al dominio (p. ej. crear una cuenta con
     * un {@code address} ya existente choca con la {@code UNIQUE}). Sin esto caia al {@code /error} default:
     * 500 + body legacy, fuera del contrato 7807. Un input valido del cliente (un duplicado) es un 409, no un
     * 5xx. El {@code detail} es FIJO a proposito: {@code e.getMessage()} filtraria el nombre de la constraint
     * y fragmentos de SQL al cliente.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La operacion viola una restriccion de integridad (p. ej. un valor unico duplicado).");
    }
}
