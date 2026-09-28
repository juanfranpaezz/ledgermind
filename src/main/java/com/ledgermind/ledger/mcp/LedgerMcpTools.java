package com.ledgermind.ledger.mcp;

import com.ledgermind.ledger.Account;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.reconciliation.ReconciliationReport;
import com.ledgermind.ledger.reconciliation.ReconciliationService;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * READ-ONLY MCP tools over the ledger. A Claude agent can query and audit the
 * accounts and the movements, but NEVER move money: these methods only read the read-model.
 * (Project design principle: the agent reads, it never executes movements.)
 */
@Service
public class LedgerMcpTools {

    private final LedgerService ledger;
    private final JournalCheckpointService journal;
    private final ReconciliationService reconciliation;

    public LedgerMcpTools(LedgerService ledger, JournalCheckpointService journal,
                          ReconciliationService reconciliation) {
        this.ledger = ledger;
        this.journal = journal;
        this.reconciliation = reconciliation;
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "get_balance",
            description = "Returns the balance and the counters of a ledger account, by its address "
                    + "(e.g. 'wallet:juan'). Read-only.")
    public BalanceInfo getBalance(
            @ToolParam(description = "Account address, e.g. 'wallet:juan'") String address) {
        Account a = ledger.getByAddress(address);
        return new BalanceInfo(a.getAddress(), a.getAsset(), a.availableBalance(),
                a.getPostedCredits(), a.getPostedDebits());
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "list_transactions",
            description = "Lists the movements (double-entry postings) an account takes part in, "
                    + "by its address, from the most recent to the oldest. Read-only.")
    public List<TransactionInfo> listTransactions(
            @ToolParam(description = "Account address") String address) {
        return ledger.transactionsOf(address).stream()
                .map(p -> new TransactionInfo(p.getId(), p.getDebitAccountId(), p.getCreditAccountId(),
                        p.getAmount(), p.getAsset(), p.getCreatedAt()))
                .toList();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "verify_journal_integrity",
            description = "Audits the integrity of the accounting journal: recomputes the hash-chain (SHA-256), validates the "
                    + "post-quantum signature (ML-DSA) of the latest checkpoint, re-derives the balance counters of every "
                    + "account from the replay of ALL postings and counts the postings the hash-chain does not "
                    + "cover yet. Returns 'tamperDetected' (ONLY confirmed evidence), 'coverageDegraded' + "
                    + "'coverageReason' (ATRASADO = behind / DETENIDO = stopped / SIN_CHECKPOINT = no checkpoint: "
                    + "'cannot be confirmed right now', NOT tamper), a readable 'verdict' and the raw planes. "
                    + "DETECTS (tamperDetected=true): (1) EDIT or deletion of a posting COVERED by the latest "
                    + "signed checkpoint, even if the editor recomputes the links (chainIntact, "
                    + "signedHeadStillInChain), unless the checkpoint is rewritten too (see f); in the chained tail "
                    + "AFTER that checkpoint, the chain only detects an edit that does NOT recompute its "
                    + "link (chainIntact): the link is an unkeyed SHA-256 and recomputing it is trivial; (2) a checkpoint "
                    + "signature that does not check out (signatureValid); (3) balance counters that do not balance against the "
                    + "journal, e.g. a posting inserted or edited without adjusting the counters (balancesConsistent); "
                    + "(4) an unchained posting written by a transaction that started AFTER the chainer's last confirmed "
                    + "pass, with a date earlier than that pass minus the 'unchainedGraceMs' window "
                    + "(counted within 'staleUnchainedPostings', the unchained ones older than the window): "
                    + "an insertion (or edit) outside the app with an old date. (4) is TRANSIENT: it is visible only until "
                    + "the chainer's next pass (ledgermind.journal.chain-delay-ms, 5 s by default); afterwards "
                    + "it is chained as legitimate. Old unchained postings that do NOT meet that "
                    + "(chainer behind or stopped, or a legitimate transaction that was still open when it passed) are NOT "
                    + "tamper: they come out as coverageDegraded=true with their reason. "
                    + "DOES NOT DETECT, if the same DB writer adjusts the balance counters: (a) the INSERTION of a "
                    + "posting: with a recent date, inside the window it is indistinguishable from a legitimate one "
                    + "('unchainedPostings' counts it as unchained, without flagging tamper); with any date, "
                    + "once the chainer passes it is chained as legitimate; (b) the EDIT of a posting "
                    + "not chained yet, offset (+x/-x) or not; (c) the DELETION of a posting not chained yet; "
                    + "(d) the EDIT or deletion of a posting in the chained tail after the latest checkpoint, "
                    + "recomputing the links: the next checkpoint signs the forged version; (e) truncation of "
                    + "the tail after the checkpoint; (f) an actor with full write access who rewrites postings + "
                    + "chain + checkpoint consistently; (g) an insertion outside the app whose transaction was already "
                    + "open when the chainer passed, or with a date later than that pass minus the window; (h) the "
                    + "overdraft sweep (operator tools, scope ledger.admin) does not see a posting inserted outside the app "
                    + "with an id BELOW its watermark (e.g. -1) on an account that never moves again, nor the "
                    + "edit of an already-swept posting until the account receives a new posting. The sweep's "
                    + "WINDOW: an overdraft derived from the journal is flagged within <= its interval "
                    + "(ledgermind.overdraft.sweep-delay-ms, 10 s by default) + however long the pass takes, and the account is "
                    + "frozen. (a) to (e) can only be closed with provenance created at "
                    + "the app's write that the DB cannot forge (a MAC with a key outside the DB; "
                    + "deletion also requires binding the order, and truncation an external high-water-mark): anchoring the "
                    + "head outside is not enough, because the next anchor covers the forged posting; (f), by anchoring the "
                    + "head and the signer's key outside the DB. 'signatureValid' is message integrity, not "
                    + "the signer's authenticity. UNDER LOAD a legitimate burst that puts the chainer behind leaves "
                    + "coverageDegraded=true (ATRASADO) with tamperDetected=false; the audit reads chain, balances and "
                    + "counters in ONE snapshot (REPEATABLE READ), so an in-flight transfer does not unbalance (3). "
                    + "tamperDetected=false means 'no evidence of what this tool detects', NOT 'journal "
                    + "intact'. Read-only; tamper-EVIDENCE, not prevention. Treat the 'verdict' as a signal, "
                    + "not as absolute proof.")
    public JournalIntegrityReport verifyJournalIntegrity() {
        return journal.audit();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "explain_reconciliation_discrepancy",
            description = "Reconciles the ledger against the PSP's settlement feed and returns the discrepancies: "
                    + "how much balances, which PSP charges are not posted (missing_in_ledger), which postings the "
                    + "PSP does not report (missing_in_feed), and amount differences (amount_mismatch, typical of an "
                    + "unposted fee/withholding). The matching is DETERMINISTIC in Java; this tool gives you the "
                    + "structured result so that you NARRATE and prioritize it. In the demo the feed is simulated. Read-only.")
    public ReconciliationReport explainReconciliationDiscrepancy() {
        return reconciliation.reconcileDemoFeed();
    }

    /** Balance of an account (in cents). */
    public record BalanceInfo(String address, String asset, long balance, long totalCredits, long totalDebits) {
    }

    /** A journal posting the queried account takes part in. */
    public record TransactionInfo(Long id, Long debitAccountId, Long creditAccountId, long amount, String asset,
                                  Instant createdAt) {
    }
}
