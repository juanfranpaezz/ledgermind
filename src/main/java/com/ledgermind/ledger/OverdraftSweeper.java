package com.ledgermind.ledger;

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
 * Barrido de sobregiro con marca de agua + congelamiento (dec-151). Opcion B del informe de medicion: en vez de
 * re-derivar el saldo en CADA transferencia (120-314 ms por transferencia a 1M asientos, dentro del lazo de
 * reintentos), un job re-deriva cada {@code sweep-delay-ms} el saldo de las cuentas que tocaron asientos NUEVOS desde
 * la ultima marca de agua, y congela la que viola su regla de sobregiro. La transferencia solo agrega
 * {@link #assertNotFrozen}: una lectura indexada.
 *
 * <p>Correccion bajo concurrencia: el barrido corre en UNA transaccion REPEATABLE READ, asi los contadores y el
 * journal se leen en la misma foto (una transferencia en vuelo es invisible entera o visible entera). La marca de agua
 * solo ELIGE que cuentas mirar (las tocadas por asientos nuevos); cada cuenta tocada se re-deriva desde TODOS sus
 * asientos en esa foto antes de decidir (un total incremental nunca decide solo). La marca de agua solo avanza hasta
 * antes del primer asiento mas joven que {@code watermark-lag-ms}, para que un asiento de id menor que commitea tarde
 * entre igual. Dos barridos a la vez (varias instancias): el perdedor falla con un error de serializacion y hace
 * rollback; NO hay reintento, la pasada siguiente cubre lo suyo (fail-closed).
 *
 * <p>DETECTA: un saldo derivado del journal que viola la regla de sobregiro de una cuenta tocada por un asiento nuevo
 * (p.ej. un asiento insertado por fuera de la app que la sobregira, o una transferencia legitima que paso el gate
 * porque los contadores estaban inflados), y la edicion de un asiento ya barrido en cuanto la cuenta recibe un asiento
 * nuevo. Ventana: <= sweep-delay-ms + lo que dure el barrido. NO DETECTA: la edicion de un asiento ya barrido (id <=
 * marca de agua) hasta que la cuenta recibe un asiento nuevo (eso lo ven la auditoria y la hash-chain si esta
 * encadenado); un asiento insertado por fuera con id POR DEBAJO de la marca de agua (p.ej. -1 con OVERRIDING SYSTEM
 * VALUE) en una cuenta que no vuelve a moverse (documentado, fijado por un test); y NO previene la primera
 * transferencia posterior a la manipulacion: congela despues.
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

    /** Resultado de una pasada: cuantas cuentas re-derivo, que rango de asientos leyo, y cuantas congelo. */
    public record SweepResult(long previousWatermark, long newWatermark, long scannedFromId, long scannedToId,
                              int touchedAccounts, int flagged, long durationMicros, boolean resetWatermark) {
    }

    /** Una marca de sobregiro activa (= congelamiento) con su evidencia. */
    public record OverdraftFlag(long id, long accountId, Instant flaggedAt, long derivedAvailable, long storedAvailable,
                                long postingIdFrom, long postingIdTo) {
    }

    @Scheduled(fixedDelayString = "${ledgermind.overdraft.sweep-delay-ms:10000}")
    public SweepResult sweep() {
        long t0 = System.nanoTime();
        SweepResult r = snapshotTx.execute(status -> sweepInSnapshot(t0));
        if (r != null && r.flagged() > 0) {
            log.warn("barrido de sobregiro: {} cuenta(s) congelada(s) (asientos {}..{})", r.flagged(),
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
            // El journal quedo por debajo de la marca (restore o truncado): re-derivar desde cero, nunca saltear.
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

        // Solo la COLA desde la marca de agua: por cuenta, lo que suma toda la cola y lo que suma hasta w2.
        Map<Long, long[]> tail = new HashMap<>();   // account -> {d, c, du, cu, firstId, lastId}
        named.query("SELECT account_id, sum(d) AS d, sum(c) AS c,"
                + " sum(CASE WHEN id <= :w2 THEN d ELSE 0 END) AS du, sum(CASE WHEN id <= :w2 THEN c ELSE 0 END) AS cu,"
                + " min(id) AS first_id, max(id) AS last_id FROM ("
                + "   SELECT debit_account_id AS account_id, amount AS d, 0::bigint AS c, id FROM posting"
                + "     WHERE id > :w AND id <= :max"
                + "   UNION ALL"
                + "   SELECT credit_account_id, 0::bigint, amount, id FROM posting WHERE id > :w AND id <= :max"
                + " ) t GROUP BY account_id", range, rs -> {
                    tail.put(rs.getLong("account_id"), new long[] {rs.getLong("d"), rs.getLong("c"),
                            rs.getLong("du"), rs.getLong("cu"), rs.getLong("first_id"), rs.getLong("last_id")});
                });

        // (a2) Cada cuenta TOCADA por la cola se re-deriva desde TODOS sus asientos (id <= max), no desde el total
        // incremental: asi la edicion de un asiento ya barrido (debajo de la marca) se ve en cuanto la cuenta vuelve a
        // moverse, y una deriva del total incremental no puede costar una deteccion en una cuenta tocada.
        Map<Long, long[]> full = new HashMap<>();   // account -> {fd, fc, fdu, fcu}
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
                    full.put(rs.getLong("account_id"), new long[] {rs.getLong("fd"), rs.getLong("fc"),
                            rs.getLong("fdu"), rs.getLong("fcu")});
                });

        List<Object[]> shadowUpserts = new ArrayList<>();
        int flagged = 0;
        List<Map<String, Object>> rows = named.queryForList("SELECT id, posted_debits, posted_credits, pending_debits,"
                + " allow_negative FROM account WHERE id IN (:ids)", new MapSqlParameterSource("ids", tail.keySet()));
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            long[] t = tail.get(id);
            long[] f = full.getOrDefault(id, new long[4]);
            long pending = ((Number) row.get("pending_debits")).longValue();
            boolean allowNegative = (Boolean) row.get("allow_negative");
            long derivedAvailable = f[1] - f[0] - pending;
            if (!allowNegative && derivedAvailable < 0) {
                long storedDebits = ((Number) row.get("posted_debits")).longValue();
                long storedCredits = ((Number) row.get("posted_credits")).longValue();
                flagged += jdbc.update("INSERT INTO overdraft_flag (account_id, derived_debits, derived_credits,"
                        + " stored_debits, stored_credits, pending_debits, derived_available, stored_available,"
                        + " posting_id_from, posting_id_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (account_id) WHERE cleared_at IS NULL DO NOTHING",
                        id, f[0], f[1], storedDebits, storedCredits, pending, derivedAvailable,
                        storedCredits - storedDebits - pending, t[4], t[5]);
            }
            shadowUpserts.add(new Object[] {id, f[2], f[3], w2});
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

    /**
     * Camino caliente de la transferencia: UNA lectura por el indice parcial overdraft_flag_one_active_per_account.
     * Sin re-derivacion. Tira {@link AccountFrozenException} si el origen o el destino estan congelados.
     */
    public void assertNotFrozen(long debitAccountId, long creditAccountId) {
        List<long[]> active = jdbc.query("SELECT account_id, id FROM overdraft_flag"
                        + " WHERE account_id IN (?, ?) AND cleared_at IS NULL LIMIT 1",
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, debitAccountId, creditAccountId);
        if (!active.isEmpty()) {
            throw new AccountFrozenException(active.get(0)[0], active.get(0)[1]);
        }
    }

    /** Marcas activas (para el operador y el tool de admin). */
    public List<OverdraftFlag> activeFlags() {
        return jdbc.query("SELECT id, account_id, flagged_at, derived_available, stored_available, posting_id_from,"
                        + " posting_id_to FROM overdraft_flag WHERE cleared_at IS NULL ORDER BY id",
                (rs, n) -> new OverdraftFlag(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }

    /**
     * Descongela: registra QUIEN y POR QUE (ambos obligatorios). Devuelve cuantas marcas levanto (0 o 1). Si el
     * saldo derivado sigue violando la regla, el proximo asiento que toque la cuenta la vuelve a marcar.
     */
    public int unfreeze(String address, String clearedBy, String reason) {
        if (clearedBy == null || clearedBy.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Descongelar exige quien (clearedBy) y por que (reason).");
        }
        Integer n = writeTx.execute(status -> jdbc.update("UPDATE overdraft_flag SET cleared_at = now(),"
                + " cleared_by = ?, clear_reason = ? WHERE cleared_at IS NULL"
                + " AND account_id = (SELECT id FROM account WHERE address = ?)", clearedBy, reason, address));
        return n == null ? 0 : n;
    }
}
