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
 * Tools MCP de SOLO LECTURA sobre el ledger. Un agente Claude puede consultar y auditar las
 * cuentas y los movimientos, pero NUNCA mover dinero: estos metodos solo leen el read-model.
 * (Principio de diseno del proyecto: el agente lee, nunca ejecuta movimientos.)
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
            description = "Devuelve el saldo y los contadores de una cuenta del ledger, por su direccion "
                    + "(ej. 'wallet:juan'). Solo lectura.")
    public BalanceInfo getBalance(
            @ToolParam(description = "Direccion de la cuenta, ej. 'wallet:juan'") String address) {
        Account a = ledger.getByAddress(address);
        return new BalanceInfo(a.getAddress(), a.getAsset(), a.availableBalance(),
                a.getPostedCredits(), a.getPostedDebits());
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "list_transactions",
            description = "Lista los movimientos (asientos de doble entrada) en los que participa una cuenta, "
                    + "por su direccion, del mas reciente al mas antiguo. Solo lectura.")
    public List<TransactionInfo> listTransactions(
            @ToolParam(description = "Direccion de la cuenta") String address) {
        return ledger.transactionsOf(address).stream()
                .map(p -> new TransactionInfo(p.getId(), p.getDebitAccountId(), p.getCreditAccountId(),
                        p.getAmount(), p.getAsset(), p.getCreatedAt()))
                .toList();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "verify_journal_integrity",
            description = "Audita la integridad del journal contable: recomputa la hash-chain (SHA-256) y "
                    + "valida la firma post-cuantica (ML-DSA) del ultimo checkpoint. Devuelve 'tamperDetected', "
                    + "un 'verdict' legible y los planos en crudo. ALCANCE: detecta EDICION/reescritura de "
                    + "asientos y del eslabon firmado; NO detecta por si solo el truncado de la cola posterior "
                    + "al checkpoint, y 'signatureValid' es integridad-de-mensaje, no autenticidad del firmante "
                    + "(la clave no esta anclada fuera de la DB). Solo lectura; tamper-EVIDENCE, no prevencion. "
                    + "Tratá el 'verdict' como una señal, no como prueba absoluta.")
    public JournalIntegrityReport verifyJournalIntegrity() {
        return journal.audit();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.read')")
    @Tool(name = "explain_reconciliation_discrepancy",
            description = "Reconcilia el ledger contra el feed de liquidacion del PSP y devuelve los descuadres: "
                    + "cuanto cuadra, que cobros del PSP no estan asentados (missing_in_ledger), que asientos el "
                    + "PSP no reporta (missing_in_feed), y diferencias de importe (amount_mismatch, tipicas de una "
                    + "comision/retencion no asentada). El matching es DETERMINISTA en Java; este tool te da el "
                    + "resultado estructurado para que lo NARRES y priorices. En el demo el feed es simulado. Solo lectura.")
    public ReconciliationReport explainReconciliationDiscrepancy() {
        return reconciliation.reconcileDemoFeed();
    }

    /** Saldo de una cuenta (en centavos). */
    public record BalanceInfo(String address, String asset, long balance, long totalCredits, long totalDebits) {
    }

    /** Un asiento del journal en el que participa la cuenta consultada. */
    public record TransactionInfo(Long id, Long debitAccountId, Long creditAccountId, long amount, String asset,
                                  Instant createdAt) {
    }
}
