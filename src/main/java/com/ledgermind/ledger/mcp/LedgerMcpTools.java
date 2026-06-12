package com.ledgermind.ledger.mcp;

import com.ledgermind.ledger.Account;
import com.ledgermind.ledger.LedgerService;
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

    public LedgerMcpTools(LedgerService ledger) {
        this.ledger = ledger;
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

    /** Saldo de una cuenta (en centavos). */
    public record BalanceInfo(String address, String asset, long balance, long totalCredits, long totalDebits) {
    }

    /** Un asiento del journal en el que participa la cuenta consultada. */
    public record TransactionInfo(Long id, Long debitAccountId, Long creditAccountId, long amount, String asset,
                                  Instant createdAt) {
    }
}
