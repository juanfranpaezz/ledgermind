package com.ledgermind.ledger.reconciliation;

import com.ledgermind.ledger.Posting;
import com.ledgermind.ledger.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the ledger against a PSP's settlement feed. It projects every posting to a
 * {@link LedgerEntry} (its {@code idempotencyKey} as the external reference + the amount) and delegates the matching
 * to the {@link ReconciliationMatcher} (deterministic, no AI). The AI, if involved, only narrates the result.
 */
@Service
public class ReconciliationService {

    private final PostingRepository postings;
    private final ReconciliationMatcher matcher = new ReconciliationMatcher();

    public ReconciliationService(PostingRepository postings) {
        this.postings = postings;
    }

    /** Reconciles the provided feed against ALL the ledger's postings (projected by idempotencyKey). */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(List<SettlementRecord> feed) {
        return matcher.reconcile(feed, ledgerEntries());
    }

    /**
     * DEMO reconciliation: it builds a simulated PSP feed from the ledger itself, injecting the three
     * typical discrepancies (an unposted fee, a posting the PSP does not report, and a PSP charge without a
     * posting), to show the matcher live. In production the feed would come from the PSP's real file.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcileDemoFeed() {
        // DETERMINISTIC order by id: that way i==1 (MISSING_IN_FEED) and i==2 (AMOUNT_MISMATCH) are stable.
        // It needs >=3 postings to show the three discrepancies; the demo seeds 5 with /api/demo/reset.
        List<Posting> all = postings.findAll(Sort.by("id"));
        List<SettlementRecord> feed = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Posting p = all.get(i);
            if (i == 1) {
                continue;                                   // this posting does NOT go into the feed -> MISSING_IN_FEED
            }
            long amount = (i == 2) ? p.getAmount() - 39 : p.getAmount();   // i==2: fee -> AMOUNT_MISMATCH
            feed.add(new SettlementRecord(p.getIdempotencyKey(), amount, p.getCreatedAt()));
        }
        // a charge the PSP reports and the ledger does not have -> MISSING_IN_LEDGER
        feed.add(new SettlementRecord("PSP-ONLY-9999", 4_300,
                all.isEmpty() ? null : all.get(0).getCreatedAt()));
        return matcher.reconcile(feed, ledgerEntries(all));
    }

    private List<LedgerEntry> ledgerEntries() {
        return ledgerEntries(postings.findAll());
    }

    private List<LedgerEntry> ledgerEntries(List<Posting> all) {
        return all.stream().map(p -> new LedgerEntry(p.getIdempotencyKey(), p.getAmount())).toList();
    }
}
