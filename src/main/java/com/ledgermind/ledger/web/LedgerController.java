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

/** API REST del ledger. Controller fino: traduce HTTP <-> {@link LedgerService} y nada mas. */
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

    /** Verifica la integridad de la hash-chain del journal (tamper-evidence). Solo lectura. */
    @GetMapping("/journal/verify")
    public JournalChainer.VerifyResult verifyJournal() {
        return journal.verify();
    }

    /**
     * Ultimo checkpoint firmado (Signed Tree Head local). Devuelve la clave publica, la firma ML-DSA y el
     * mensaje EXACTO firmado, para que un tercero pueda verificar la firma por su cuenta — contra una clave
     * que en prod debe anclarse fuera de la DB. 204 si aun no hay checkpoints.
     */
    @GetMapping("/journal/checkpoint")
    public ResponseEntity<CheckpointView> latestCheckpoint() {
        return checkpoints.latest()
                .map(cp -> ResponseEntity.ok(CheckpointView.from(cp)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Verifica el ultimo checkpoint en planos separados: firma valida, cadena integra (SHA-256 recomputado),
     * eslabon firmado aun presente, y si es ademas la cabeza viva (informativo). El tamper de CONTENIDO lo
     * delata {@code chainIntact}, no la firma.
     */
    @GetMapping("/journal/checkpoint/verify")
    public JournalCheckpointService.CheckpointVerification verifyCheckpoint() {
        return checkpoints.verifyLatest();
    }

    /**
     * Auditoria consolidada (mismo dato que el tool MCP {@code verify_journal_integrity}): hash-chain +
     * firma post-cuantica en un solo informe con veredicto legible. Solo lectura.
     */
    @GetMapping("/journal/audit")
    public JournalCheckpointService.JournalIntegrityReport auditJournal() {
        return checkpoints.audit();
    }

    /**
     * Reconcilia el ledger contra un feed de liquidacion del PSP (el body es la lista de registros del feed).
     * El matching es determinista en Java; devuelve los descuadres clasificados. Solo lectura.
     */
    @PostMapping("/reconciliation")
    public ReconciliationReport reconcile(@RequestBody List<SettlementRecord> feed) {
        // Validacion en el BORDE: un feed real de un PSP trae filas sucias. Sin esto, un body 'null', un
        // elemento [null] o un externalRef nulo reventaban el matcher (groupingBy con clave null) con un NPE
        // crudo -> 500 en un endpoint publico. Lo clasificamos como lo que es: un request invalido (400).
        if (feed == null || feed.stream().anyMatch(
                r -> r == null || r.externalRef() == null || r.externalRef().isBlank())) {
            throw new IllegalArgumentException(
                    "El feed de conciliacion no puede ser nulo y cada registro requiere un externalRef no vacio.");
        }
        return reconciliation.reconcile(feed);
    }

    // --- DTOs (records): nunca exponemos las entidades JPA directamente ---

    public record CreateAccountRequest(
            @NotBlank @Size(max = 128) String address,
            @NotBlank @Size(min = 3, max = 3) String asset,
            Boolean allowNegative) {
    }

    public record TransferRequest(
            @NotBlank @Size(max = 128) String debitAddress,
            @NotBlank @Size(max = 128) String creditAddress,
            // Cota superior: evita overflow de los contadores BIGINT por una cuenta allow_negative.
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
