package com.ledgermind.ledger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aplica transferencias de dinero como asientos de doble entrada, seguras bajo concurrencia.
 *
 * <p>Estrategia de concurrencia: OPTIMISTIC LOCKING + RETRY.
 * El bucle de reintento vive AFUERA de la transaccion: cada intento abre su propia transaccion
 * y re-lee las cuentas frescas. Si dos transferencias chocan sobre la misma cuenta, una falla al
 * commitear con {@link ObjectOptimisticLockingFailureException}; la atrapamos y reintentamos con
 * el estado ya actualizado. Asi es imposible gastar dos veces el mismo saldo.
 *
 * <p>Idempotencia EXACTLY-ONCE bajo concurrencia: el chequeo {@code findByIdempotencyKey} resuelve los
 * reintentos secuenciales, pero NO una carrera (dos requests con la misma clave ven la clave libre a la
 * vez). Para esa carrera, la {@code UNIQUE} de {@code idempotency_key} es la red real: cuando atrapa al
 * duplicado lanzamos {@link DataIntegrityViolationException}, y la convertimos en un REPLAY del asiento
 * ya commiteado (no en un 500). Asi un duplicado concurrente devuelve la respuesta original, igual que un
 * reintento secuencial.
 */
@Service
public class TransferService {

    /** Cuantas veces reintentamos una transferencia que pierde la carrera antes de rendirnos. */
    private static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final TransactionTemplate tx;
    /** Reintentos acumulados por conflicto transitorio. Observable para que un test afirme que hubo contencion real. */
    private final AtomicLong retries = new AtomicLong(0);

    public TransferService(AccountRepository accounts,
                           PostingRepository postings,
                           PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.postings = postings;
        // Transacciones programaticas: necesitamos controlar el limite transaccional a mano
        // para que el retry quede AFUERA (cada intento = transaccion nueva).
        this.tx = new TransactionTemplate(txManager);
    }

    public Posting transfer(TransferCommand cmd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> apply(cmd));
            } catch (ConcurrencyFailureException conflict) {
                // Conflicto TRANSITORIO de concurrencia. Cubre AMBOS casos reintentables, que extienden
                // ConcurrencyFailureException: (1) el optimista por @Version
                // ({@link ObjectOptimisticLockingFailureException}) cuando dos transferencias chocan sobre
                // la misma cuenta; y (2) el DEADLOCK de Postgres (40P01 -> CannotAcquireLockException) cuando
                // dos transferencias opuestas (A->B y B->A) lockean las filas en orden inverso. Antes solo se
                // atrapaba el optimista y el deadlock escapaba como 500 pese a ser perfectamente reintentable.
                // La transaccion se revirtio entera (no escribimos nada). Reintentamos desde cero.
                retries.incrementAndGet();
                if (attempt >= MAX_ATTEMPTS) {
                    throw new TransferConflictException(attempt);
                }
                backoffBeforeRetry(attempt);   // backoff exponencial + jitter: corta el thundering herd
            } catch (DataIntegrityViolationException duplicate) {
                // Carrera de IDEMPOTENCIA: otro request con la misma clave inserto primero y chocamos con
                // la UNIQUE. La operacion YA quedo aplicada exactamente una vez -> devolvemos ese asiento
                // (replay), no un error. Si la violacion fuese de OTRA constraint, no habra asiento con
                // esta clave y re-lanzamos la excepcion original.
                Posting existing = postings.findByIdempotencyKey(cmd.idempotencyKey())
                        .orElseThrow(() -> duplicate);
                return replayOrConflict(existing, cmd);
            }
        }
    }

    /** Reintentos acumulados por conflicto transitorio (optimista o deadlock). Lo usa el spike de concurrencia
     *  para afirmar que la contencion realmente se ejercito (un test que pasa sin un solo retry no prueba nada). */
    public long retryCount() {
        return retries.get();
    }

    /**
     * Backoff exponencial ACOTADO + jitter antes de reintentar. Sin esto, bajo alta contencion los N perdedores
     * recompiten en lockstep (thundering herd) y transferencias con saldo pueden agotar los reintentos y fallar
     * con 409 aunque habia fondos. El tope (20 ms) mantiene la latencia y los tests acotados.
     */
    private static void backoffBeforeRetry(int attempt) {
        long base = Math.min(20L, 1L << (attempt - 1));                        // 1,2,4,8,16 -> tope 20 ms
        long sleepMs = base + ThreadLocalRandom.current().nextLong(base + 1);  // + jitter en [0, base]
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransferConflictException(attempt);
        }
    }

    /**
     * Una idempotency-key identifica UNA operacion. Si el asiento que ya existe coincide con el pedido,
     * es un REPLAY legitimo (mismo resultado). Si los parametros DIFIEREN, el cliente reuso la clave para
     * otra cosa: devolver el original lo enganiaria, asi que lanzamos {@link IdempotencyConflictException}.
     */
    private Posting replayOrConflict(Posting existing, TransferCommand cmd) {
        boolean sameOperation = existing.getDebitAccountId().equals(cmd.debitAccountId())
                && existing.getCreditAccountId().equals(cmd.creditAccountId())
                && existing.getAmount() == cmd.amount();
        if (sameOperation) {
            return existing;
        }
        throw new IdempotencyConflictException(cmd.idempotencyKey());
    }

    /** Un intento. Corre dentro de UNA transaccion. Aca viven los invariantes del ledger. */
    private Posting apply(TransferCommand cmd) {
        // 1) Idempotencia: si ya existe un asiento con esta clave, es un replay (mismos params) o un
        //    conflicto (la clave se reuso para otra operacion). No duplicamos en ningun caso.
        var existing = postings.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), cmd);
        }

        // 1.5) Invariante: una transferencia mueve dinero ENTRE dos cuentas DISTINTAS. Validarlo en Java
        //      (ademas del CHECK posting_distinct_accounts en la DB) lo clasifica como un 400 limpio, no como
        //      un 500 opaco por la violacion del CHECK que se colaria por el catch de idempotencia.
        if (cmd.debitAccountId().equals(cmd.creditAccountId())) {
            throw new IllegalArgumentException("No se puede transferir una cuenta a si misma.");
        }

        // 2) Cargamos las dos cuentas. Son entidades 'managed': sus cambios se flushean al commit
        //    con el chequeo de version (optimistic locking).
        Account debit = accounts.findById(cmd.debitAccountId())
                .orElseThrow(() -> new AccountNotFoundException(cmd.debitAccountId()));
        Account credit = accounts.findById(cmd.creditAccountId())
                .orElseThrow(() -> new AccountNotFoundException(cmd.creditAccountId()));

        // 3) Invariantes de negocio (ademas de los CHECK en la DB: segunda linea de defensa).
        if (!debit.getAsset().equals(credit.getAsset())) {
            throw new IllegalArgumentException("No se puede transferir entre assets distintos");
        }
        if (!debit.isAllowNegative() && debit.availableBalance() < cmd.amount()) {
            throw new InsufficientFundsException(debit.getId(), debit.availableBalance(), cmd.amount());
        }

        // 4) Doble entrada: debito una cuenta y acredito la otra por el mismo importe.
        debit.applyDebit(cmd.amount());
        credit.applyCredit(cmd.amount());

        // 5) Registramos el asiento inmutable. La UNIQUE de idempotency_key es la red real contra duplicados.
        Posting posting = new Posting(debit.getId(), credit.getId(), cmd.amount(), debit.getAsset(),
                cmd.idempotencyKey());
        return postings.save(posting);
        // Al cerrar la transaccion, JPA emite por cada cuenta:
        //   UPDATE account SET ..., version = version + 1 WHERE id = ? AND version = ?
        // Si otra transaccion ya la cambio -> 0 filas -> ObjectOptimisticLockingFailureException -> retry.
    }
}
