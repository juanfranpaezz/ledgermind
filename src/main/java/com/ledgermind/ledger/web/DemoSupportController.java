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
 * Soporte SOLO para la demo visual ({@code static/index.html}). Existe unicamente bajo el perfil
 * {@code demo}: en produccion estos endpoints NO se cargan. Son cinco, los unicos de /api que un anonimo puede
 * llamar (y solo bajo {@code demo}): POST reset (ledger limpio y firmado), POST idempotency (la misma transferencia
 * dos veces con una clave fija, del lado del server), POST tamper (edicion maliciosa de un asiento por SQL directo,
 * para mostrar EN VIVO que la hash-chain lo detecta), POST reconcile y GET audit. La pagina de la demo debe llamar
 * solo estos cinco: cualquier otro /api pide X-API-Key. No es parte del dominio: es andamiaje de demostracion.
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

    /** Reinicia a un escenario limpio: 3 cuentas, 5 transferencias (claves tipo ORD-xxxx), cadena firmada. */
    @PostMapping("/reset")
    DemoMessage reset() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:ana", "ARS", false);
        ledger.createAccount("wallet:beto", "ARS", false);
        // La idempotencyKey es el id de orden del cliente (sirve de referencia externa para reconciliar).
        ledger.transfer("external:funding", "wallet:ana", 100_000, "ORD-1001");
        ledger.transfer("external:funding", "wallet:ana", 50_000, "ORD-1002");
        ledger.transfer("wallet:ana", "wallet:beto", 30_000, "ORD-1003");
        ledger.transfer("wallet:ana", "wallet:beto", 12_500, "ORD-1004");
        ledger.transfer("external:funding", "wallet:beto", 8_000, "ORD-1005");
        // Encadenar y firmar AHORA (no esperar al job async). Si el job @Scheduled corre en paralelo y
        // gana la carrera, su violacion de PK/UNIQUE es benigna: el scheduler completa la cadena igual.
        try {
            chainer.chainPendingPostings();
            checkpoints.checkpointIfHeadAdvanced();
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // el job programado ya encadeno/firmo esta cabeza; nada que hacer
        }
        return new DemoMessage("Estado limpio: 3 cuentas, 5 transferencias (ORD-1001..1005), hash-chain firmada con ML-DSA.");
    }

    /** Reconcilia el ledger contra un feed simulado del PSP (con descuadres inyectados) para la demo. */
    @PostMapping("/reconcile")
    ReconciliationReport reconcile() {
        return reconciliation.reconcileDemoFeed();
    }

    /**
     * Simulates an attacker with DB access who edits the amount of the latest CHAINED posting, so the hash-chain is
     * what breaks. Editing the latest posting instead could hit one the chainer has not linked yet (it runs every
     * 5 s): then the chain stayed intact and only the balance replay fired (docs-truth gate r3, 2026-09-26).
     */
    @PostMapping("/tamper")
    DemoMessage tamper() {
        Long id = jdbc.queryForList("SELECT posting_id FROM posting_hash ORDER BY seq DESC LIMIT 1", Long.class)
                .stream().findFirst().orElse(null);
        if (id == null) {
            return new DemoMessage("No hay asientos encadenados para alterar. Reinicia la demo primero.");
        }
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", id);
        return new DemoMessage("Se altero por SQL directo el monto del asiento #" + id
                + " (simulando un atacante con acceso a la base). La firma NO se toco.");
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
