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
 * OPERATOR MCP tools (scope {@code ledger.admin}), separate from {@link LedgerMcpTools}, which is read-only.
 * They move no money: they list and lift overdraft freezes. Who unfreezes is taken from the authenticated token,
 * not from a parameter the agent could make up.
 */
@Service
public class LedgerAdminMcpTools {

    static final String DETECTION_WINDOW = "An overdraft is detected within <= the sweep interval"
            + " (ledgermind.overdraft.sweep-delay-ms, 10 s by default) + however long the sweep takes, and then the account"
            + " is frozen; every account touched by a new posting is re-derived from ALL its postings."
            + " DOES NOT DETECT: the edit of an already-swept posting (before the watermark) until the account"
            + " receives a new posting; a posting inserted outside the app with an id BELOW the watermark (e.g."
            + " -1 with OVERRIDING SYSTEM VALUE) on an account that never moves again; and it does not prevent the first"
            + " transfer after the tampering (it freezes afterwards). In the demo the token only carries"
            + " ledger.read: this tool (scope ledger.admin) cannot be used there.";

    private final OverdraftSweeper sweeper;

    public LedgerAdminMcpTools(OverdraftSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.admin')")
    @Tool(name = "list_frozen_accounts",
            description = "Lists the accounts FROZEN by the overdraft sweep, with the evidence: balance derived"
                    + " from the journal vs stored balance and the range of postings. " + DETECTION_WINDOW)
    public List<OverdraftFlag> listFrozenAccounts() {
        return sweeper.activeFlags();
    }

    @PreAuthorize("hasAuthority('SCOPE_ledger.admin')")
    @Tool(name = "unfreeze_account",
            description = "Unfreezes an account flagged for overdraft. Records WHO (the token's subject) and WHY."
                    + " If the derived balance still violates the rule, the next posting that touches it freezes it"
                    + " again. " + DETECTION_WINDOW)
    public String unfreezeAccount(
            @ToolParam(description = "Account address, e.g. 'wallet:juan'") String address,
            @ToolParam(description = "Reason for the unfreeze (mandatory, it is recorded)") String reason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("unfreeze_account requires an authenticated caller.");
        }
        int cleared = sweeper.unfreeze(address, auth.getName(), reason);
        return cleared == 0 ? "Account " + address + " had no active freeze."
                : "Account " + address + " unfrozen by " + auth.getName() + ".";
    }
}
