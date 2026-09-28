package com.ledgermind.ledger;

import java.math.BigInteger;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Overdraft sweep with a watermark + freeze. Instead of
 * re-deriving the balance on EVERY transfer (120-314 ms per transfer at 1M postings, inside the retry
 * loop), a job re-derives every {@code sweep-delay-ms} the balance of the accounts touched by NEW postings since
 * the last watermark, and freezes the one that violates its overdraft rule. The transfer only adds
 * {@link #assertNotFrozen}: one indexed read.
 *
 * <p>Correctness under concurrency: the sweep runs in ONE REPEATABLE READ transaction, so the counters and the
 * journal are read in the same snapshot (an in-flight transfer is either entirely invisible or entirely visible). The watermark
 * only CHOOSES which accounts to look at (the ones touched by new postings); every touched account is re-derived from ALL its
 * postings in that snapshot before deciding (an incremental total never decides on its own). The watermark only advances up to
 * just before the first posting younger than {@code watermark-lag-ms}, so that a posting with a lower id that commits late
 * still gets in. Two sweeps at once (several instances): the loser fails with a serialization error and rolls
 * back; there is NO retry, the next pass covers its share (fail-closed).
 *
 * <p>DETECTS: a journal-derived balance that violates the overdraft rule of an account touched by a new posting
 * (e.g. a posting inserted outside the app that overdraws it, or a legitimate transfer that passed the gate
 * because the counters were inflated), and the edit of an already-swept posting as soon as the account receives a new
 * posting. Window: <= sweep-delay-ms + however long the sweep takes. DOES NOT DETECT: the edit of an already-swept posting (id <=
 * watermark) until the account receives a new posting (the audit sees it, and so does the hash-chain if it is
 * chained); a posting inserted outside the app with an id BELOW the watermark (e.g. -1 with OVERRIDING SYSTEM
 * VALUE) on an account that never moves again (documented, pinned by a test); and it does NOT prevent the first
 * transfer after the tampering: it freezes afterwards.
 */
@Service
public class OverdraftSweeper {

    private static final Logger log = LoggerFactory.getLogger(OverdraftSweeper.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate snapshotTx;
    private final TransactionTemplate writeTx;
    private final long watermarkLagMs;

    public OverdraftSweeper(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            @Value("${ledgermind.overdraft.watermark-lag-ms:2000}") long watermarkLagMs) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.snapshotTx = new TransactionTemplate(txManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writeTx = new TransactionTemplate(txManager);
        this.watermarkLagMs = watermarkLagMs;
    }

    /** Result of a pass: how many accounts it re-derived, which range of postings it read, and how many it froze. */
    public record SweepResult(long previousWatermark, long newWatermark, long scannedFromId, long scannedToId,
                              int touchedAccounts, int flagged, long durationMicros, boolean resetWatermark) {
    }

    /** An active overdraft flag (= freeze) with its evidence. */
    public record OverdraftFlag(long id, long accountId, Instant flaggedAt, long derivedAvailable, long storedAvailable,
                                long postingIdFrom, long postingIdTo) {
    }

    // The first pass runs right at startup (initial delay 0) unless the property says otherwise; tests that call
    // sweep() by hand set it high so the scheduled startup pass cannot race them on the FOR UPDATE row.
    @Scheduled(fixedDelayString = "${ledgermind.overdraft.sweep-delay-ms:10000}",
            initialDelayString = "${ledgermind.overdraft.sweep-initial-delay-ms:0}")
    public SweepResult sweep() {
        long t0 = System.nanoTime();
        SweepResult r = snapshotTx.execute(status -> sweepInSnapshot(t0));
        if (r != null && r.flagged() > 0) {
            log.warn("overdraft sweep: {} account(s) frozen (postings {}..{})", r.flagged(),
                    r.scannedFromId(), r.scannedToId());
        }
        return r;
    }

    private SweepResult sweepInSnapshot(long t0) {
        long w = jdbc.queryForObject(
                "SELECT watermark_posting_id FROM overdraft_sweep_state WHERE id = 1 FOR UPDATE", Long.class);
        Long maxOrNull = jdbc.queryForObject("SELECT max(id) FROM posting", Long.class);
        long max = maxOrNull == null ? 0L : maxOrNull;
        long previous = w;
        boolean reset = false;
        if (max < w) {
            // The journal ended up below the watermark (restore or truncation): re-derive from scratch, never skip.
            jdbc.update("DELETE FROM account_derived_total");
            w = 0L;
            reset = true;
        }
        if (max == w) {
            return finish(t0, previous, w, w + 1, max, 0, 0, reset);
        }
        Instant now = Instant.now();
        MapSqlParameterSource range = new MapSqlParameterSource().addValue("w", w).addValue("max", max);
        Long firstYoung = named.queryForObject("SELECT min(id) FROM posting WHERE id > :w AND id <= :max"
                        + " AND created_at > :young AND created_at <= :future",
                new MapSqlParameterSource(range.getValues())
                        .addValue("young", Timestamp.from(now.minusMillis(watermarkLagMs)))
                        .addValue("future", Timestamp.from(now.plusMillis(watermarkLagMs))),
                Long.class);
        long w2 = firstYoung == null ? max : Math.max(w, firstYoung - 1);
        range.addValue("w2", w2);

        // Only the TAIL from the watermark: per account, what the whole tail sums and what it sums up to w2.
        // Only first/last posting id per account are read from the tail. The sums are NOT read as long: a NUMERIC
        // sum above Long.MAX made rs.getLong throw, and the whole pass (the overdraft freeze) stopped.
        Map<Long, long[]> tail = new HashMap<>();   // account -> {firstId, lastId}
        named.query("SELECT account_id, sum(d) AS d, sum(c) AS c,"
                + " sum(CASE WHEN id <= :w2 THEN d ELSE 0 END) AS du, sum(CASE WHEN id <= :w2 THEN c ELSE 0 END) AS cu,"
                + " min(id) AS first_id, max(id) AS last_id FROM ("
                + "   SELECT debit_account_id AS account_id, amount AS d, 0::bigint AS c, id FROM posting"
                + "     WHERE id > :w AND id <= :max"
                + "   UNION ALL"
                + "   SELECT credit_account_id, 0::bigint, amount, id FROM posting WHERE id > :w AND id <= :max"
                + " ) t GROUP BY account_id", range, rs -> {
                    tail.put(rs.getLong("account_id"), new long[] {rs.getLong("first_id"), rs.getLong("last_id")});
                });

        // (a2) Every account TOUCHED by the tail is re-derived from ALL its postings (id <= max), not from the incremental
        // total: that way the edit of an already-swept posting (below the watermark) is seen as soon as the account moves
        // again, and a drift of the incremental total cannot cost a detection on a touched account.
        // Exact sums: PostgreSQL sum(bigint) is NUMERIC and can exceed Long.MAX; the decision is taken in BigInteger.
        Map<Long, BigInteger[]> full = new HashMap<>();   // account -> {fd, fc, fdu, fcu}
        named.query("SELECT account_id, sum(d) AS fd, sum(c) AS fc,"
                + " sum(CASE WHEN id <= :w2 THEN d ELSE 0 END) AS fdu, sum(CASE WHEN id <= :w2 THEN c ELSE 0 END) AS fcu"
                + " FROM ("
                + "   SELECT debit_account_id AS account_id, amount AS d, 0::bigint AS c, id FROM posting"
                + "     WHERE debit_account_id IN (:ids) AND id <= :max"
                + "   UNION ALL"
                + "   SELECT credit_account_id, 0::bigint, amount, id FROM posting"
                + "     WHERE credit_account_id IN (:ids) AND id <= :max"
                + " ) t GROUP BY account_id",
                new MapSqlParameterSource(range.getValues()).addValue("ids", tail.keySet()), rs -> {
                    full.put(rs.getLong("account_id"), new BigInteger[] {exact(rs.getBigDecimal("fd")),
                            exact(rs.getBigDecimal("fc")), exact(rs.getBigDecimal("fdu")),
                            exact(rs.getBigDecimal("fcu"))});
                });

        List<Object[]> shadowUpserts = new ArrayList<>();
        int flagged = 0;
        List<Map<String, Object>> rows = named.queryForList("SELECT id, posted_debits, posted_credits, pending_debits,"
                + " allow_negative FROM account WHERE id IN (:ids)", new MapSqlParameterSource("ids", tail.keySet()));
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            long[] t = tail.get(id);
            BigInteger[] f = full.getOrDefault(id, ZERO_SUMS);
            long pending = ((Number) row.get("pending_debits")).longValue();
            boolean allowNegative = (Boolean) row.get("allow_negative");
            BigInteger derivedAvailable = f[1].subtract(f[0]).subtract(BigInteger.valueOf(pending));
            if (!allowNegative && derivedAvailable.signum() < 0) {
                long storedDebits = ((Number) row.get("posted_debits")).longValue();
                long storedCredits = ((Number) row.get("posted_credits")).longValue();
                flagged += jdbc.update("INSERT INTO overdraft_flag (account_id, derived_debits, derived_credits,"
                        + " stored_debits, stored_credits, pending_debits, derived_available, stored_available,"
                        + " posting_id_from, posting_id_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (account_id) WHERE cleared_at IS NULL DO NOTHING",
                        id, saturate(f[0]), saturate(f[1]), storedDebits, storedCredits, pending,
                        saturate(derivedAvailable), saturate(BigInteger.valueOf(storedCredits)
                                .subtract(BigInteger.valueOf(storedDebits)).subtract(BigInteger.valueOf(pending))),
                        t[0], t[1]);
            }
            shadowUpserts.add(new Object[] {id, saturate(f[2]), saturate(f[3]), w2});
        }
        jdbc.batchUpdate("INSERT INTO account_derived_total (account_id, derived_debits, derived_credits,"
                + " as_of_posting_id) VALUES (?, ?, ?, ?) ON CONFLICT (account_id) DO UPDATE SET"
                + " derived_debits = excluded.derived_debits, derived_credits = excluded.derived_credits,"
                + " as_of_posting_id = excluded.as_of_posting_id", shadowUpserts);
        return finish(t0, previous, w2, w + 1, max, tail.size(), flagged, reset);
    }

    private SweepResult finish(long t0, long previous, long newWatermark, long from, long to, int touched,
                               int flagged, boolean reset) {
        long micros = (System.nanoTime() - t0) / 1_000L;
        jdbc.update("UPDATE overdraft_sweep_state SET watermark_posting_id = ?, last_sweep_at = now(),"
                + " last_scanned_from = ?, last_scanned_to = ?, last_touched_accounts = ?, last_flagged = ?,"
                + " last_duration_micros = ? WHERE id = 1", newWatermark, from, to, touched, flagged, micros);
        return new SweepResult(previous, newWatermark, from, to, touched, flagged, micros, reset);
    }

    private static final BigInteger[] ZERO_SUMS = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};

    private static BigInteger exact(java.math.BigDecimal sum) {
        return sum == null ? BigInteger.ZERO : sum.toBigIntegerExact();
    }

    /**
     * The flag and shadow-total columns are BIGINT (no migration). A value outside the 64-bit range is stored
     * SATURATED at Long.MIN_VALUE / Long.MAX_VALUE: the evidence keeps its sign and reads "beyond the range";
     * the freeze decision itself was taken on the exact value.
     */
    static long saturate(BigInteger v) {
        if (v.bitLength() < 64) {
            return v.longValue();
        }
        return v.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    /**
     * Transfer hot path: ONE read through the partial index overdraft_flag_one_active_per_account.
     * No re-derivation. Throws {@link AccountFrozenException} if the source or the destination is frozen.
     */
    public void assertNotFrozen(long debitAccountId, long creditAccountId) {
        List<long[]> active = jdbc.query("SELECT account_id, id FROM overdraft_flag"
                        + " WHERE account_id IN (?, ?) AND cleared_at IS NULL LIMIT 1",
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, debitAccountId, creditAccountId);
        if (!active.isEmpty()) {
            throw new AccountFrozenException(active.get(0)[0], active.get(0)[1]);
        }
    }

    /** Active flags (for the operator and the admin tool). */
    public List<OverdraftFlag> activeFlags() {
        return jdbc.query("SELECT id, account_id, flagged_at, derived_available, stored_available, posting_id_from,"
                        + " posting_id_to FROM overdraft_flag WHERE cleared_at IS NULL ORDER BY id",
                (rs, n) -> new OverdraftFlag(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }

    /**
     * Unfreezes: records WHO and WHY (both mandatory). Returns how many flags it lifted (0 or 1). If the
     * derived balance still violates the rule, the next posting that touches the account flags it again.
     */
    public int unfreeze(String address, String clearedBy, String reason) {
        if (clearedBy == null || clearedBy.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Unfreezing requires who (clearedBy) and why (reason).");
        }
        Integer n = writeTx.execute(status -> jdbc.update("UPDATE overdraft_flag SET cleared_at = now(),"
                + " cleared_by = ?, clear_reason = ? WHERE cleared_at IS NULL"
                + " AND account_id = (SELECT id FROM account WHERE address = ?)", clearedBy, reason, address));
        return n == null ? 0 : n;
    }
}
