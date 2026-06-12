package com.ledgermind.ledger.web;

import com.ledgermind.ledger.JournalChainer;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.reconciliation.ReconciliationReport;
import com.ledgermind.ledger.reconciliation.ReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soporte SOLO para la demo visual ({@code static/index.html}). Existe unicamente bajo el perfil
 * {@code demo}: en produccion estos endpoints NO se cargan. Permite (1) reiniciar el ledger a un estado
 * limpio y firmado, y (2) simular una edicion maliciosa de un asiento por SQL directo, para mostrar EN VIVO
 * que la hash-chain lo detecta. No es parte del dominio: es andamiaje de demostracion.
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

    /** Simula un atacante con acceso a la DB que edita el monto del ultimo asiento (rompe la cadena). */
    @PostMapping("/tamper")
    DemoMessage tamper() {
        Long id = jdbc.queryForObject("SELECT max(id) FROM posting", Long.class);
        if (id == null) {
            return new DemoMessage("No hay asientos para alterar. Reinicia la demo primero.");
        }
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", id);
        return new DemoMessage("Se altero por SQL directo el monto del asiento #" + id
                + " (simulando un atacante con acceso a la base). La firma NO se toco.");
    }

    record DemoMessage(String message) {
    }
}
