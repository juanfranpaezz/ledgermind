package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    List<Posting> findByDebitAccountIdOrCreditAccountIdOrderByIdDesc(Long debitAccountId, Long creditAccountId);

    /**
     * Asientos que todavia NO tienen eslabon en la hash-chain, en orden de id, de a lotes.
     * Se busca por AUSENCIA en {@code posting_hash} (no por un watermark de id): asi un asiento cuyo id
     * IDENTITY es menor pero commitea DESPUES del watermark no queda nunca sin encadenar (no se saltea).
     */
    @Query("select p from Posting p where not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id) order by p.id asc")
    List<Posting> findUnchainedOrderByIdAsc(Limit limit);
}
