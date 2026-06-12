package com.ledgermind.ledger.web;

import com.ledgermind.ledger.JournalChainer;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
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
    private final JdbcTemplate jdbc;

    DemoSupportController(LedgerService ledger, JournalChainer chainer,
                          JournalCheckpointService checkpoints, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.chainer = chainer;
        this.checkpoints = checkpoints;
        this.jdbc = jdbc;
    }

    /** Reinicia a un escenario limpio: 3 cuentas, 2 transferencias, cadena encadenada y firmada. */
    @PostMapping("/reset")
    DemoMessage reset() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:ana", "ARS", false);
        ledger.createAccount("wallet:beto", "ARS", false);
        ledger.transfer("external:funding", "wallet:ana", 100_000, "seed-ana");
        ledger.transfer("wallet:ana", "wallet:beto", 30_000, "ana-beto-1");
        chainer.chainPendingPostings();              // encadenar ahora (no esperar al job async)
        checkpoints.checkpointIfHeadAdvanced();      // y firmar la cabeza ahora
        return new DemoMessage("Estado limpio: 3 cuentas, 2 transferencias, hash-chain firmada con ML-DSA.");
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
