package com.ledgermind.ledger.web;

import com.ledgermind.ledger.Account;
import com.ledgermind.ledger.JournalChainer;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.Posting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API REST del ledger. Controller fino: traduce HTTP <-> {@link LedgerService} y nada mas. */
@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledger;
    private final JournalChainer journal;

    public LedgerController(LedgerService ledger, JournalChainer journal) {
        this.ledger = ledger;
        this.journal = journal;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView createAccount(@RequestBody @Valid CreateAccountRequest req) {
        Account a = ledger.createAccount(req.address(), req.asset(), Boolean.TRUE.equals(req.allowNegative()));
        return AccountView.from(a);
    }

    @GetMapping("/accounts/{address}")
    public AccountView getAccount(@PathVariable String address) {
        return AccountView.from(ledger.getByAddress(address));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public PostingView transfer(@RequestBody @Valid TransferRequest req) {
        Posting p = ledger.transfer(req.debitAddress(), req.creditAddress(), req.amount(), req.idempotencyKey());
        return PostingView.from(p);
    }

    /** Verifica la integridad de la hash-chain del journal (tamper-evidence). Solo lectura. */
    @GetMapping("/journal/verify")
    public JournalChainer.VerifyResult verifyJournal() {
        return journal.verify();
    }

    // --- DTOs (records): nunca exponemos las entidades JPA directamente ---

    public record CreateAccountRequest(
            @NotBlank String address,
            @NotBlank @Size(min = 3, max = 3) String asset,
            Boolean allowNegative) {
    }

    public record TransferRequest(
            @NotBlank String debitAddress,
            @NotBlank String creditAddress,
            @Positive long amount,
            @NotBlank String idempotencyKey) {
    }

    public record AccountView(String address, String asset, long balance,
                              long postedDebits, long postedCredits, long version) {
        static AccountView from(Account a) {
            return new AccountView(a.getAddress(), a.getAsset(), a.availableBalance(),
                    a.getPostedDebits(), a.getPostedCredits(), a.getVersion());
        }
    }

    public record PostingView(Long id, Long debitAccountId, Long creditAccountId,
                              long amount, String asset, String idempotencyKey, Instant createdAt) {
        static PostingView from(Posting p) {
            return new PostingView(p.getId(), p.getDebitAccountId(), p.getCreditAccountId(),
                    p.getAmount(), p.getAsset(), p.getIdempotencyKey(), p.getCreatedAt());
        }
    }
}
