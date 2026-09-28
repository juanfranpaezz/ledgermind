package com.ledgermind.ledger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integrity of the balance READ-MODEL against the JOURNAL (tamper-evidence of the counter, not of the chain).
 *
 * <p>The {@code posted_debits} / {@code posted_credits} counters of {@link Account} are ADVANCED with
 * {@code +=} in {@link TransferService} when the posting is written, and afterwards they are NEVER recomputed. If
 * someone edits the amount of an already-written posting (by direct SQL, for example), the counter keeps
 * the OLD arithmetic and the journal holds the NEW truth: the two disagree and nobody notices,
 * because the three readers of the counter ({@code LedgerController.AccountView}, the MCP tool
 * {@code get_balance} and {@link Account#availableBalance()}, which decides the overdraft rejection) read
 * the cached number without ever re-deriving it.
 *
 * <p>This class REPLAYS the journal: it sums the amounts of every posting per account (debit and credit)
 * and compares against what is stored. It reports the account, both numbers and the difference. It is DETECTION: it does not
 * correct the counter or touch the write path.
 *
 * <p>Snapshot: the comparison is ONE SQL statement (every account LEFT JOIN a GROUP BY aggregate of the journal), so
 * the stored counters and the journal sums come from the same statement snapshot even under READ COMMITTED; the
 * earlier READ COMMITTED transient false positive (postings and accounts read by separate statements) no longer
 * applies. {@link JournalCheckpointService#audit()} calls it inside its REPEATABLE READ transaction, so it also agrees
 * with the hash-chain and coverage reads of the same audit.
 *
 * <p>Cost: the database aggregates every posting on every call (O(n) in the journal, no Posting or Account entity is
 * hydrated). Conservation (sum of all posted_debits == sum of all posted_credits) is not checked separately: it is
 * implied whenever {@code consistent} is true, because every posting row carries one amount, one debit account and one
 * distinct credit account (FK to account), so the journal side is conserved by construction and consistent counters
 * equal it account by account.
 */
@Component
public class AccountBalanceVerifier {

    /**
     * Replay as one statement: each account with its stored counters and the journal's debit/credit sums for it
     * (0 when it has no postings), plus the journal size, all read in one statement snapshot. The inner UNION ALL /
     * GROUP BY is the same projection OverdraftSweeper uses; the scalar count is evaluated once per statement.
     */
    private static final String REPLAY_SQL = "SELECT a.id, a.address, a.posted_debits, a.posted_credits,"
            + " coalesce(j.d, 0) AS journal_debits, coalesce(j.c, 0) AS journal_credits,"
            + " (SELECT count(*) FROM posting) AS postings_replayed"
            + " FROM account a LEFT JOIN ("
            + "   SELECT account_id, sum(d) AS d, sum(c) AS c FROM ("
            + "     SELECT debit_account_id AS account_id, amount AS d, 0::bigint AS c FROM posting"
            + "     UNION ALL"
            + "     SELECT credit_account_id, 0::bigint, amount FROM posting"
            + "   ) t GROUP BY account_id"
            + " ) j ON j.account_id = a.id ORDER BY a.id";

    private final JdbcTemplate jdbc;

    public AccountBalanceVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Recomputes the counters of EVERY account by replaying the journal and compares them with what is stored.
     * Read-only; it mutates nothing.
     */
    @Transactional(readOnly = true)
    public BalanceVerifyResult verify() {
        List<AccountBalanceMismatch> mismatches = new ArrayList<>();
        long[] checkedAndReplayed = new long[2];
        jdbc.query(REPLAY_SQL, rs -> {
            checkedAndReplayed[0]++;
            checkedAndReplayed[1] = rs.getLong("postings_replayed");
            long storedDebits = rs.getLong("posted_debits");
            long storedCredits = rs.getLong("posted_credits");
            // sum() of BIGINT is NUMERIC: read it exactly. A journal sum above Long.MAX_VALUE (only reachable
            // out-of-band, the counters are BIGINT) must come out as a mismatch, not as "Bad value for type long".
            BigInteger journalDebits = rs.getBigDecimal("journal_debits").toBigIntegerExact();
            BigInteger journalCredits = rs.getBigDecimal("journal_credits").toBigIntegerExact();
            BigInteger debitsDifference = BigInteger.valueOf(storedDebits).subtract(journalDebits);
            BigInteger creditsDifference = BigInteger.valueOf(storedCredits).subtract(journalCredits);
            if (debitsDifference.signum() != 0 || creditsDifference.signum() != 0) {
                mismatches.add(new AccountBalanceMismatch(rs.getLong("id"), rs.getString("address"),
                        storedDebits, journalDebits, debitsDifference,
                        storedCredits, journalCredits, creditsDifference));
            }
        });
        return new BalanceVerifyResult(mismatches.isEmpty(), checkedAndReplayed[0], checkedAndReplayed[1],
                List.copyOf(mismatches));
    }

    /**
     * An account whose cached counter does NOT match the journal replay. It carries BOTH numbers and the
     * difference on each side, so the mismatch can be read without going back to the database. The journal sums and the
     * differences are {@link BigInteger}: an out-of-band journal can sum above {@code Long.MAX_VALUE}.
     */
    public record AccountBalanceMismatch(Long accountId, String address,
                                         long storedPostedDebits, BigInteger journalPostedDebits,
                                         BigInteger postedDebitsDifference,
                                         long storedPostedCredits, BigInteger journalPostedCredits,
                                         BigInteger postedCreditsDifference) {

        /** Readable line for the audit verdict and for the logs. */
        public String describe() {
            StringBuilder sb = new StringBuilder(address).append(" (id ").append(accountId).append("):");
            if (postedDebitsDifference.signum() != 0) {
                sb.append(" debitos almacenados ").append(storedPostedDebits)
                        .append(" vs journal ").append(journalPostedDebits)
                        .append(" (difference ").append(postedDebitsDifference).append(")");
            }
            if (postedCreditsDifference.signum() != 0) {
                if (postedDebitsDifference.signum() != 0) {
                    sb.append(",");
                }
                sb.append(" creditos almacenados ").append(storedPostedCredits)
                        .append(" vs journal ").append(journalPostedCredits)
                        .append(" (difference ").append(postedCreditsDifference).append(")");
            }
            return sb.toString();
        }
    }

    /** Replay result: whether the counters balance, how much was checked and the mismatches found. */
    public record BalanceVerifyResult(boolean consistent, long accountsChecked, long postingsReplayed,
                                      List<AccountBalanceMismatch> mismatches) {
    }
}
