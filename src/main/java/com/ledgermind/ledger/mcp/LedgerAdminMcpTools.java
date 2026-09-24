package com.ledgermind.ledger.mcp;

import com.ledgermind.ledger.OverdraftSweeper;
import com.ledgermind.ledger.OverdraftSweeper.OverdraftFlag;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Tools MCP de OPERADOR (scope {@code ledger.admin}), separadas de {@link LedgerMcpTools} que es de solo lectura.
 * No mueven dinero: listan y levantan congelamientos por sobregiro. Quien descongela se toma del token autenticado,
 * no de un parametro que el agente pueda inventar.
 */
@Service
public class LedgerAdminMcpTools {

    static final String DETECTION_WINDOW = "Un sobregiro se detecta dentro de <= intervalo del barrido"
            + " (ledgermind.overdraft.sweep-delay-ms, 10 s por defecto) + lo que dure el barrido, y entonces la cuenta"
            + " queda congelada; cada cuenta tocada por un asiento nuevo se re-deriva desde TODOS sus asientos."
            + " NO DETECTA: la edicion de un asiento ya barrido (anterior a la marca de agua) hasta que la cuenta"
            + " recibe un asiento nuevo; un asiento insertado por fuera con id POR DEBAJO de la marca de agua (p.ej."
            + " -1 con OVERRIDING SYSTEM VALUE) en una cuenta que no vuelve a moverse; y no previene la primera"
            + " transferencia posterior a la manipulacion (congela despues). En la demo el token solo lleva"
            + " ledger.read: este tool (scope ledger.admin) no se puede usar ahi.";

    private final OverdraftSweeper sweeper;

    public LedgerAdminMcpTools(OverdraftSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.admin')")
    @Tool(name = "list_frozen_accounts",
            description = "Lista las cuentas CONGELADAS por el barrido de sobregiro, con la evidencia: saldo derivado"
                    + " del journal vs saldo guardado y el rango de asientos. " + DETECTION_WINDOW)
    public List<OverdraftFlag> listFrozenAccounts() {
        return sweeper.activeFlags();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.admin')")
    @Tool(name = "unfreeze_account",
            description = "Descongela una cuenta marcada por sobregiro. Registra QUIEN (el sujeto del token) y POR QUE."
                    + " Si el saldo derivado sigue violando la regla, el proximo asiento que la toque la vuelve a"
                    + " congelar. " + DETECTION_WINDOW)
    public String unfreezeAccount(
            @ToolParam(description = "Direccion de la cuenta, ej. 'wallet:juan'") String address,
            @ToolParam(description = "Motivo del descongelamiento (obligatorio, queda registrado)") String reason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("unfreeze_account exige un llamador autenticado.");
        }
        int cleared = sweeper.unfreeze(address, auth.getName(), reason);
        return cleared == 0 ? "La cuenta " + address + " no tenia un congelamiento activo."
                : "Cuenta " + address + " descongelada por " + auth.getName() + ".";
    }
}
