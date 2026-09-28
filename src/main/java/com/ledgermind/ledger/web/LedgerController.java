package com.ledgermind.ledger.web;

import com.ledgermind.ledger.Account;
import com.ledgermind.ledger.JournalChainer;
import com.ledgermind.ledger.JournalCheckpoint;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.Posting;
import com.ledgermind.ledger.reconciliation.ReconciliationReport;
import com.ledgermind.ledger.reconciliation.ReconciliationService;
import com.ledgermind.ledger.reconciliation.SettlementRecord;
import jakarta.validation.Valid;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The ledger's REST API. A thin controller: it translates HTTP <-> {@link LedgerService} and nothing more. */
@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledger;
    private final JournalChainer journal;
    private final JournalCheckpointService checkpoints;
    private final ReconciliationService reconciliation;

    public LedgerController(LedgerService ledger, JournalChainer journal,
                            JournalCheckpointService checkpoints, ReconciliationService reconciliation) {
        this.ledger = ledger;
        this.journal = journal;
        this.checkpoints = checkpoints;
        this.reconciliation = reconciliation;
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

    /** Verifies the integrity of the journal's hash-chain (tamper-evidence). Read-only. */
    @GetMapping("/journal/verify")
    public JournalChainer.VerifyResult verifyJournal() {
        return journal.verify();
    }

    /**
     * Latest signed checkpoint (local Signed Tree Head). It returns the public key, the ML-DSA signature and the
     * EXACT signed message, so that a third party can verify the signature on its own — against a key
     * that in prod must be anchored outside the DB. 204 if there are no checkpoints yet.
     */
    @GetMapping("/journal/checkpoint")
    public ResponseEntity<CheckpointView> latestCheckpoint() {
        return checkpoints.latest()
                .map(cp -> ResponseEntity.ok(CheckpointView.from(cp)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Verifies the latest checkpoint on separate planes: valid signature, intact chain (recomputed SHA-256),
     * signed link still present, and whether it is also the live head (informational). CONTENT tamper is
     * exposed by {@code chainIntact}, not by the signature.
     */
    @GetMapping("/journal/checkpoint/verify")
    public JournalCheckpointService.CheckpointVerification verifyCheckpoint() {
        return checkpoints.verifyLatest();
    }

    /**
     * Consolidated audit (same data as the MCP tool {@code verify_journal_integrity}): hash-chain +
     * post-quantum signature in a single report with a readable verdict. Read-only.
     */
    @GetMapping("/journal/audit")
    public JournalCheckpointService.JournalIntegrityReport auditJournal() {
        return checkpoints.audit();
    }

    /**
     * Reconciles the ledger against a PSP settlement feed (the body is the list of feed records).
     * The matching is deterministic in Java; it returns the classified discrepancies. Read-only.
     */
    @PostMapping("/reconciliation")
    public ReconciliationReport reconcile(@RequestBody List<SettlementRecord> feed) {
        // Validation at the EDGE: a real PSP feed brings dirty rows. Without this, a 'null' body, a
        // [null] element or a null externalRef blew up the matcher (groupingBy with a null key) with a raw
        // NPE -> a 500 on an API endpoint. We classify it as what it is: an invalid request (400).
        if (feed == null || feed.stream().anyMatch(
                r -> r == null || r.externalRef() == null || r.externalRef().isBlank())) {
            throw new IllegalArgumentException(
                    "The reconciliation feed cannot be null and every record requires a non-empty externalRef.");
        }
        return reconciliation.reconcile(feed);
    }

    // --- DTOs (records): we never expose the JPA entities directly ---

    public record CreateAccountRequest(
            @NotBlank @Size(max = 128) String address,
            @NotBlank @Size(min = 3, max = 3) String asset,
            Boolean allowNegative) {
    }

    public record TransferRequest(
            @NotBlank @Size(max = 128) String debitAddress,
            @NotBlank @Size(max = 128) String creditAddress,
            // Upper bound: prevents overflow of the BIGINT counters through an allow_negative account.
            @Positive @Max(1_000_000_000_000L) long amount,
            @NotBlank @Size(max = 64) String idempotencyKey) {
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

    public record CheckpointView(long chainSeq, String headHash, String algorithm,
                                 String signedMessage, String publicKeyBase64, String signature,
                                 Instant signedAt) {
        static CheckpointView from(JournalCheckpoint cp) {
            return new CheckpointView(cp.getChainSeq(), cp.getHeadHash(), cp.getAlgorithm(),
                    JournalCheckpointService.checkpointMessageString(cp.getChainSeq(), cp.getHeadHash()),
                    cp.getPublicKey(), cp.getSignature(), cp.getSignedAt());
        }
    }
}
