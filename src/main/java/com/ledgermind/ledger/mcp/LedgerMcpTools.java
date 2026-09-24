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
            description = "Audita la integridad del journal contable: recomputa la hash-chain (SHA-256), valida la "
                    + "firma post-cuantica (ML-DSA) del ultimo checkpoint, re-deriva los contadores de saldo de cada "
                    + "cuenta desde el replay de TODOS los asientos y cuenta los asientos que la hash-chain todavia no "
                    + "cubre. Devuelve 'tamperDetected' (SOLO evidencia confirmada), 'coverageDegraded' + "
                    + "'coverageReason' (ATRASADO / DETENIDO / SIN_CHECKPOINT = 'ahora no se puede confirmar', NO es "
                    + "tamper), un 'verdict' legible y los planos en crudo. "
                    + "DETECTA (tamperDetected=true): (1) EDICION o borrado de un asiento CUBIERTO por el ultimo "
                    + "checkpoint firmado, aun si el editor recomputa los eslabones (chainIntact, "
                    + "signedHeadStillInChain), salvo que reescriba tambien el checkpoint (ver f); en la cola "
                    + "encadenada POSTERIOR a ese checkpoint, la cadena solo detecta la edicion que NO recomputa su "
                    + "eslabon (chainIntact): el eslabon es SHA-256 sin clave y recomputarlo es trivial; (2) una firma "
                    + "de checkpoint que no cierra (signatureValid); (3) contadores de saldo que no cierran contra el "
                    + "journal, p.ej. un asiento insertado o editado sin ajustar los contadores (balancesConsistent); "
                    + "(4) un asiento sin encadenar escrito por una transaccion que empezo DESPUES de la ultima pasada "
                    + "confirmada del encadenador, con fecha anterior a esa pasada menos la ventana 'unchainedGraceMs' "
                    + "(cuenta dentro de 'staleUnchainedPostings', los sin encadenar mas viejos que la ventana): "
                    + "insercion (o edicion) por fuera de la app con fecha vieja. (4) es TRANSITORIO: se ve solo hasta "
                    + "la proxima pasada del encadenador (ledgermind.journal.chain-delay-ms, 5 s por defecto); despues "
                    + "queda encadenado como legitimo. Los asientos viejos sin encadenar que NO cumplen eso "
                    + "(encadenador atrasado o detenido, o una transaccion legitima que seguia abierta cuando paso) NO "
                    + "son tamper: salen como coverageDegraded=true con su motivo. "
                    + "NO DETECTA, si el mismo escritor de DB ajusta los contadores de saldo: (a) la INSERCION de un "
                    + "asiento: con fecha reciente, dentro de la ventana es indistinguible de uno legitimo "
                    + "('unchainedPostings' lo cuenta como sin encadenar, sin marcar tamper); con cualquier fecha, "
                    + "una vez que pasa el encadenador queda encadenado como legitimo; (b) la EDICION de un asiento "
                    + "aun sin encadenar, compensada (+x/-x) o no; (c) el BORRADO de un asiento aun sin encadenar; "
                    + "(d) la EDICION o el borrado de un asiento de la cola encadenada posterior al ultimo checkpoint, "
                    + "recomputando los eslabones: el proximo checkpoint firma la version falsa; (e) el truncado de "
                    + "la cola posterior al checkpoint; (f) un actor con escritura total que reescribe asientos + "
                    + "cadena + checkpoint de forma consistente; (g) una insercion por fuera cuya transaccion ya estaba "
                    + "abierta cuando paso el encadenador, o con fecha posterior a esa pasada menos la ventana; (h) el "
                    + "barrido de sobregiro (tools de operador, scope ledger.admin) no ve un asiento insertado por fuera "
                    + "con id POR DEBAJO de su marca de agua (p.ej. -1) en una cuenta que no vuelve a moverse, ni la "
                    + "edicion de un asiento ya barrido hasta que la cuenta recibe un asiento nuevo. VENTANA del "
                    + "barrido: un sobregiro derivado del journal se marca dentro de <= su intervalo "
                    + "(ledgermind.overdraft.sweep-delay-ms, 10 s por defecto) + lo que dure la pasada, y la cuenta queda "
                    + "congelada. (a) a (e) solo se cierran con procedencia creada en "
                    + "la escritura de la app que la DB no pueda falsificar (un MAC con una clave fuera de la DB; el "
                    + "borrado exige ademas ligar el orden, y el truncado un high-water-mark externo): anclar la "
                    + "cabeza afuera no alcanza, porque el proximo anclaje cubre el asiento falso; (f), anclando la "
                    + "cabeza y la clave del firmante fuera de la DB. 'signatureValid' es integridad-de-mensaje, no "
                    + "autenticidad del firmante. BAJO CARGA una rafaga legitima que atrasa al encadenador deja "
                    + "coverageDegraded=true (ATRASADO) con tamperDetected=false; la auditoria lee cadena, saldos y "
                    + "contadores en UNA foto (REPEATABLE READ), asi que una transferencia en vuelo no descuadra (3). "
                    + "tamperDetected=false significa 'sin evidencia de lo que este tool detecta', NO 'journal "
                    + "integro'. Solo lectura; tamper-EVIDENCE, no prevencion. Tratá el 'verdict' como una señal, "
                    + "no como prueba absoluta.")
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
