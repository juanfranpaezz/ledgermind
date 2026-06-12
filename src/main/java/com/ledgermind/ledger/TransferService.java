package com.ledgermind.ledger;

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
 */
@Service
public class TransferService {

    /** Cuantas veces reintentamos una transferencia que pierde la carrera antes de rendirnos. */
    private static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final TransactionTemplate tx;

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
            } catch (ObjectOptimisticLockingFailureException conflict) {
                // Perdimos la carrera: el saldo cambio mientras decidiamos.
                // La transaccion se revirtio entera (no escribimos nada). Reintentamos desde cero.
                if (attempt >= MAX_ATTEMPTS) {
                    throw new TransferConflictException(attempt);
                }
            }
        }
    }

    /** Un intento. Corre dentro de UNA transaccion. Aca viven los invariantes del ledger. */
    private Posting apply(TransferCommand cmd) {
        // 1) Idempotencia: si ya existe un asiento con esta clave, devolvemos ese (replay), no duplicamos.
        var existing = postings.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
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
