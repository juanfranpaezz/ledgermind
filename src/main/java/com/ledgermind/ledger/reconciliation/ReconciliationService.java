package com.ledgermind.ledger.reconciliation;

import com.ledgermind.ledger.Posting;
import com.ledgermind.ledger.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconcilia el ledger contra el feed de liquidacion de un PSP. Proyecta cada asiento a un
 * {@link LedgerEntry} (su {@code idempotencyKey} como referencia externa + el importe) y delega el cruce
 * en el {@link ReconciliationMatcher} (determinista, sin IA). La IA, si participa, solo narra el resultado.
 */
@Service
public class ReconciliationService {

    private final PostingRepository postings;
    private final ReconciliationMatcher matcher = new ReconciliationMatcher();

    public ReconciliationService(PostingRepository postings) {
        this.postings = postings;
    }

    /** Reconcilia el feed provisto contra TODOS los asientos del ledger (proyectados por idempotencyKey). */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(List<SettlementRecord> feed) {
        return matcher.reconcile(feed, ledgerEntries());
    }

    /**
     * Reconciliacion de DEMO: arma un feed simulado del PSP a partir del propio ledger, inyectando los tres
     * descuadres tipicos (una comision no asentada, un asiento que el PSP no reporta, y un cobro del PSP sin
     * asiento), para mostrar el matcher en vivo. En produccion el feed vendria del archivo real del PSP.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcileDemoFeed() {
        // Orden DETERMINISTA por id: asi i==1 (MISSING_IN_FEED) e i==2 (AMOUNT_MISMATCH) son estables.
        // Requiere >=3 asientos para mostrar los tres descuadres; el demo siembra 5 con /api/demo/reset.
        List<Posting> all = postings.findAll(Sort.by("id"));
        List<SettlementRecord> feed = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Posting p = all.get(i);
            if (i == 1) {
                continue;                                   // este asiento NO entra al feed -> MISSING_IN_FEED
            }
            long amount = (i == 2) ? p.getAmount() - 39 : p.getAmount();   // i==2: comision -> AMOUNT_MISMATCH
            feed.add(new SettlementRecord(p.getIdempotencyKey(), amount, p.getCreatedAt()));
        }
        // un cobro que el PSP reporta y el ledger no tiene -> MISSING_IN_LEDGER
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
