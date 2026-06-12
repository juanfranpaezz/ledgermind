package com.ledgermind.ledger;

import org.springframework.stereotype.Service;

/**
 * Servicio de aplicacion del ledger: orquesta el dominio para la capa web.
 * Resuelve direcciones de cuenta a ids y delega el movimiento de dinero en {@link TransferService}.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final TransferService transfers;

    public LedgerService(AccountRepository accounts, TransferService transfers) {
        this.accounts = accounts;
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
}
