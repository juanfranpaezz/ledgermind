package com.ledgermind.ledger;

/**
 * The account has an ACTIVE overdraft flag: the sweep re-derived its balance from the journal and it violates its
 * overdraft rule. It accepts no transfers (neither as source nor as destination) until an operator unfreezes it.
 */
public class AccountFrozenException extends RuntimeException {

    private final long accountId;
    private final long flagId;

    public AccountFrozenException(long accountId, long flagId) {
        super("Account " + accountId + " FROZEN for a detected overdraft (flag #" + flagId + "): the sweep"
                + " re-derived its balance from the journal and it violates its overdraft rule. It accepts no transfers until"
                + " an operator unfreezes it (unfreeze_account; who and why are recorded).");
        this.accountId = accountId;
        this.flagId = flagId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getFlagId() {
        return flagId;
    }
}
