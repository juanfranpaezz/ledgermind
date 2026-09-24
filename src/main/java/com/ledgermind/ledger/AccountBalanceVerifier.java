package com.ledgermind.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integridad del READ-MODEL de saldos contra el JOURNAL (tamper-evidence del contador, no de la cadena).
 *
 * <p>Los contadores {@code posted_debits} / {@code posted_credits} de {@link Account} se ADELANTAN con
 * {@code +=} en {@link TransferService} cuando se escribe el asiento, y despues NUNCA se recomputan. Si
 * alguien edita el importe de un asiento ya escrito (por SQL directo, por ejemplo), el contador conserva
 * la aritmetica VIEJA y el journal guarda la verdad NUEVA: los dos quedan en desacuerdo y nadie lo nota,
 * porque los tres lectores del contador ({@code LedgerController.AccountView}, el tool MCP
 * {@code get_balance} y {@link Account#availableBalance()}, que decide el rechazo por descubierto) leen
 * el numero cacheado sin re-derivarlo jamas.
 *
 * <p>Esta clase REPLAYA el journal: suma los importes de todos los asientos por cuenta (debito y credito)
 * y compara contra lo almacenado. Reporta la cuenta, los dos numeros y la diferencia. Es DETECCION: no
 * corrige el contador ni toca el camino de escritura.
 *
 * <p>LIMITE conocido: corre en una transaccion de solo lectura en READ COMMITTED, asi que una
 * transferencia que commitea ENTRE el barrido de asientos y el de cuentas puede producir un descuadre
 * TRANSITORIO (falso positivo). Es el lado seguro del error (avisa de mas, nunca de menos), pero para
 * correrlo bajo carga conviene un snapshot unico (REPEATABLE READ) o una replica quiescente.
 */
@Component
public class AccountBalanceVerifier {

    /** Tamanio de lote del replay: pagina por keyset (id) para no cargar el journal entero en memoria. */
    private static final int BATCH = 500;

    private final AccountRepository accounts;
    private final PostingRepository postings;

    public AccountBalanceVerifier(AccountRepository accounts, PostingRepository postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    /**
     * Recomputa los contadores de CADA cuenta replayando el journal y los compara con lo almacenado.
     * Solo lectura; no muta nada.
     */
    @Transactional(readOnly = true)
    public BalanceVerifyResult verify() {
        Replay replay = replayJournal();
        List<AccountBalanceMismatch> mismatches = new ArrayList<>();
        long checked = 0;
        for (Account a : accounts.findAll()) {
            checked++;
            long[] sums = replay.byAccountId().getOrDefault(a.getId(), new long[2]);
            long journalDebits = sums[0];
            long journalCredits = sums[1];
            if (a.getPostedDebits() != journalDebits || a.getPostedCredits() != journalCredits) {
                mismatches.add(new AccountBalanceMismatch(a.getId(), a.getAddress(),
                        a.getPostedDebits(), journalDebits, a.getPostedDebits() - journalDebits,
                        a.getPostedCredits(), journalCredits, a.getPostedCredits() - journalCredits));
            }
        }
        return new BalanceVerifyResult(mismatches.isEmpty(), checked, replay.postingsReplayed(),
                List.copyOf(mismatches));
    }

    /** Suma por cuenta: [0] = debitos, [1] = creditos. Pagina por id ascendente (keyset), sin N+1. */
    private Replay replayJournal() {
        Map<Long, long[]> byAccountId = new HashMap<>();
        long replayed = 0;
        long afterId = 0L;
        List<Posting> batch;
        while (!(batch = postings.findByIdGreaterThanOrderByIdAsc(afterId, Limit.of(BATCH))).isEmpty()) {
            for (Posting p : batch) {
                byAccountId.computeIfAbsent(p.getDebitAccountId(), k -> new long[2])[0] += p.getAmount();
                byAccountId.computeIfAbsent(p.getCreditAccountId(), k -> new long[2])[1] += p.getAmount();
                afterId = p.getId();
                replayed++;
            }
        }
        return new Replay(byAccountId, replayed);
    }

    private record Replay(Map<Long, long[]> byAccountId, long postingsReplayed) {
    }

    /**
     * Una cuenta cuyo contador cacheado NO coincide con el replay del journal. Lleva los DOS numeros y la
     * diferencia de cada lado, para que el descuadre se pueda leer sin volver a la base.
     */
    public record AccountBalanceMismatch(Long accountId, String address,
                                         long storedPostedDebits, long journalPostedDebits,
                                         long postedDebitsDifference,
                                         long storedPostedCredits, long journalPostedCredits,
                                         long postedCreditsDifference) {

        /** Linea legible para el verdict del audit y para los logs. */
        public String describe() {
            StringBuilder sb = new StringBuilder(address).append(" (id ").append(accountId).append("):");
            if (postedDebitsDifference != 0) {
                sb.append(" debitos almacenados ").append(storedPostedDebits)
                        .append(" vs journal ").append(journalPostedDebits)
                        .append(" (diferencia ").append(postedDebitsDifference).append(")");
            }
            if (postedCreditsDifference != 0) {
                if (postedDebitsDifference != 0) {
                    sb.append(",");
                }
                sb.append(" creditos almacenados ").append(storedPostedCredits)
                        .append(" vs journal ").append(journalPostedCredits)
                        .append(" (diferencia ").append(postedCreditsDifference).append(")");
            }
            return sb.toString();
        }
    }

    /** Resultado del replay: si los contadores cierran, cuanto se reviso y los descuadres encontrados. */
    public record BalanceVerifyResult(boolean consistent, long accountsChecked, long postingsReplayed,
                                      List<AccountBalanceMismatch> mismatches) {
    }
}
