package com.ledgermind.ledger.web;

import com.ledgermind.ledger.JournalChainer;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.Posting;
import com.ledgermind.ledger.reconciliation.ReconciliationReport;
import com.ledgermind.ledger.reconciliation.ReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support ONLY for the visual demo ({@code static/index.html}). It exists only under the
 * {@code demo} profile: in production these endpoints are NOT loaded. There are five, the only /api endpoints an anonymous caller can
 * call (and only under {@code demo}): POST reset (clean, signed ledger), POST idempotency (the same transfer
 * twice with a fixed key, server-side), POST tamper (a malicious edit of a posting by direct SQL,
 * to show LIVE that the hash-chain detects it), POST reconcile and GET audit. The demo page must call
 * only these five: any other /api path asks for an X-API-Key. It is not part of the domain: it is demo scaffolding.
 */
@RestController
@Profile("demo")
@RequestMapping("/api/demo")
class DemoSupportController {

    private final LedgerService ledger;
    private final JournalChainer chainer;
    private final JournalCheckpointService checkpoints;
    private final ReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    DemoSupportController(LedgerService ledger, JournalChainer chainer,
                          JournalCheckpointService checkpoints, ReconciliationService reconciliation,
                          JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.chainer = chainer;
        this.checkpoints = checkpoints;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
    }

    /** Resets to a clean scenario: 3 accounts, 5 transfers (ORD-xxxx style keys), signed chain. */
    @PostMapping("/reset")
    DemoMessage reset() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:ana", "ARS", false);
        ledger.createAccount("wallet:beto", "ARS", false);
        // The idempotencyKey is the client's order id (it serves as the external reference for reconciliation).
        ledger.transfer("external:funding", "wallet:ana", 100_000, "ORD-1001");
        ledger.transfer("external:funding", "wallet:ana", 50_000, "ORD-1002");
        ledger.transfer("wallet:ana", "wallet:beto", 30_000, "ORD-1003");
        ledger.transfer("wallet:ana", "wallet:beto", 12_500, "ORD-1004");
        ledger.transfer("external:funding", "wallet:beto", 8_000, "ORD-1005");
        // Chain and sign NOW (do not wait for the async job). If the @Scheduled job runs in parallel and
        // wins the race, its PK/UNIQUE violation is benign: the scheduler completes the chain anyway.
        try {
            chainer.chainPendingPostings();
            checkpoints.checkpointIfHeadAdvanced();
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // the scheduled job already chained/signed this head; nothing to do
        }
        return new DemoMessage("Clean state: 3 accounts, 5 transfers (ORD-1001..1005), hash-chain signed with ML-DSA.");
    }

    /** Reconciles the ledger against a simulated PSP feed (with injected discrepancies) for the demo. */
    @PostMapping("/reconcile")
    ReconciliationReport reconcile() {
        return reconciliation.reconcileDemoFeed();
    }

    /**
     * Simulates an attacker with DB access who edits the amount of the latest CHAINED posting, so the hash-chain is
     * what breaks. Editing the latest posting instead could hit one the chainer has not linked yet (it runs every
 * 5 s): then the chain stayed intact and only the balance replay fired.
     */
    @PostMapping("/tamper")
    DemoMessage tamper() {
        Long id = jdbc.queryForList("SELECT posting_id FROM posting_hash ORDER BY seq DESC LIMIT 1", Long.class)
                .stream().findFirst().orElse(null);
        if (id == null) {
            return new DemoMessage("There are no chained postings to alter. Reset the demo first.");
        }
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", id);
        return new DemoMessage("Direct SQL altered the amount of posting #" + id
                + " (simulating an attacker with database access). The signature was NOT touched.");
    }

    /**
     * Fixed idempotency key of the demo: every call after the first replays, so anonymous callers add at most one
     * posting per reset. The demo tamper edits the latest CHAINED posting; once that is this posting (the chainer links
     * it within one cycle), later idempotency calls get 409 (the stored amount no longer matches the request) and still
     * add no posting, until the next reset.
     */
    static final String DEMO_IDEMPOTENCY_KEY = "demo-dup";

    /**
     * The idempotency demo, run server-side so the page needs no keyed endpoint: read beto, the SAME transfer
     * twice with the fixed key, read beto again. The second call replays the first posting.
     */
    @PostMapping("/idempotency")
    DemoIdempotency idempotency() {
        long before = ledger.getByAddress("wallet:beto").availableBalance();
        Posting first = ledger.transfer("wallet:ana", "wallet:beto", 5_000, DEMO_IDEMPOTENCY_KEY);
        Posting second = ledger.transfer("wallet:ana", "wallet:beto", 5_000, DEMO_IDEMPOTENCY_KEY);
        long after = ledger.getByAddress("wallet:beto").availableBalance();
        return new DemoIdempotency(first.getId(), second.getId(), first.getId().equals(second.getId()),
                before, after);
    }

    /** The same report as the keyed {@code GET /api/journal/audit}, reachable anonymously under the demo profile. */
    @GetMapping("/audit")
    JournalCheckpointService.JournalIntegrityReport audit() {
        return checkpoints.audit();
    }

    record DemoIdempotency(long firstPostingId, long secondPostingId, boolean samePosting,
                           long betoBefore, long betoAfter) {
    }

    record DemoMessage(String message) {
    }
}
