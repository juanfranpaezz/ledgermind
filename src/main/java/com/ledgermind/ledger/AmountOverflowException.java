package com.ledgermind.ledger;

/**
 * An amount, a counter or a total would leave the signed 64-bit range. The write (or the report) is rejected and
 * mapped to 422; the value is never wrapped silently.
 */
public class AmountOverflowException extends RuntimeException {

    public AmountOverflowException(String message) {
        super(message);
    }
}
