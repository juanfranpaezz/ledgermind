package com.ledgermind.ledger;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Ledger application service: orchestrates the domain for the web layer and for the MCP tools.
 * It resolves account addresses to ids and delegates the movement of money to {@link TransferService}.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final TransferService transfers;

    public LedgerService(AccountRepository accounts, PostingRepository postings, TransferService transfers) {
        this.accounts = accounts;
        this.postings = postings;
        this.transfers = transfers;
    }

    public Account createAccount(String address, String asset, boolean allowNegative) {
        return accounts.save(new Account(address, asset, allowNegative));
    }

    public Account getByAddress(String address) {
        return accounts.findByAddress(address)
                .orElseThrow(() -> new AccountNotFoundException(address));
    }

    public Posting transfer(String debitAddress, String creditAddress, long amount, String idempotencyKey) {
        Long debitId = getByAddress(debitAddress).getId();
        Long creditId = getByAddress(creditAddress).getId();
        return transfers.transfer(new TransferCommand(debitId, creditId, amount, idempotencyKey));
    }

    /** Movements (postings) an account takes part in. Read-only. */
    public List<Posting> transactionsOf(String address) {
        Long id = getByAddress(address).getId();
        return postings.findByDebitAccountIdOrCreditAccountIdOrderByIdDesc(id, id);
    }
}
