package com.ledgermind.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    List<Posting> findByDebitAccountIdOrCreditAccountIdOrderByIdDesc(Long debitAccountId, Long creditAccountId);

    /**
     * Asientos con id MAYOR a {@code afterId}, ascendente y de a lotes: paginacion keyset del journal entero sin
     * pagar el offset. No longer used by {@link AccountBalanceVerifier}, which now aggregates the journal in one
     * SQL statement; currently no caller in src/main.
     */
    List<Posting> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);

    /**
     * Asientos que todavia NO tienen eslabon en la hash-chain, en orden de id, de a lotes.
     * Se busca por AUSENCIA en {@code posting_hash} (no por un watermark de id): asi un asiento cuyo id
     * IDENTITY es menor pero commitea DESPUES del watermark no queda nunca sin encadenar (no se saltea).
     */
    @Query("select p from Posting p where not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id) order by p.id asc")
    List<Posting> findUnchainedOrderByIdAsc(Limit limit);

    /**
     * Cuantos asientos NO tienen eslabon en la hash-chain (misma definicion por AUSENCIA que
     * {@link #findUnchainedOrderByIdAsc}). La auditoria lo usa para declarar que parte del journal NO cubre.
     */
    @Query("select count(p) from Posting p where not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id)")
    long countUnchained();

    /** Asientos sin eslabon creados ANTES de {@code cutoff}: los que el encadenador ya deberia haber cubierto. */
    @Query("select count(p) from Posting p where p.createdAt < :cutoff and not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id)")
    long countUnchainedCreatedBefore(@Param("cutoff") Instant cutoff);
}
