package com.ledgermind.ledger.web;

import com.ledgermind.ledger.AccountNotFoundException;
import com.ledgermind.ledger.AmountOverflowException;
import com.ledgermind.ledger.IdempotencyConflictException;
import com.ledgermind.ledger.AccountFrozenException;
import com.ledgermind.ledger.InsufficientFundsException;
import com.ledgermind.ledger.TransferConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain exceptions into standard HTTP responses (RFC 7807 ProblemDetail).
 * It centralizes error handling: the controllers need no try/catch.
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

    /** 423 Locked: the account is frozen by the overdraft sweep; it is neither a 500 nor insufficient funds. */
    @ExceptionHandler(AccountFrozenException.class)
    ProblemDetail handleFrozen(AccountFrozenException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, e.getMessage());
        pd.setTitle("Account frozen for overdraft");
        pd.setProperty("accountId", e.getAccountId());
        pd.setProperty("overdraftFlagId", e.getFlagId());
        return pd;
    }

    /** 422: an amount, counter or total would leave the 64-bit range; the write was rejected, nothing wrapped. */
    @ExceptionHandler(AmountOverflowException.class)
    ProblemDetail handleAmountOverflow(AmountOverflowException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Amount outside the 64-bit range");
        return pd;
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
     * Safety net for ANY data-integrity violation that escapes the domain (e.g. creating an account with
     * an existing {@code address} hits the {@code UNIQUE}). Without it, it fell into the default {@code /error}:
     * 500 + legacy body, outside the 7807 contract. A valid client input (a duplicate) is a 409, not a
     * 5xx. The {@code detail} is FIXED on purpose: {@code e.getMessage()} would leak the constraint name
     * and SQL fragments to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The operation violates an integrity constraint (e.g. a duplicated unique value).");
    }

    /**
     * Unexpected, unrecoverable internal state. Real use case: {@code MlDsaJournalSigner.verify()} could not
     * verify the checkpoint signature because the persisted key/signature is STRUCTURALLY corrupt (invalid
     * Base64, broken X.509). That is NOT evidence of tamper (a realistic signature with valid Base64 returns false and
     * exposes the tamper); it is a SERVER failure. We map it to 500 INSIDE the RFC 7807 contract with a
     * FIXED detail (it leaks no internals): fail-loud, but not a raw 500 outside the contract.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The operation could not be completed because of an unexpected internal state.");
    }
}
