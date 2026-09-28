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
     * Postings with an id GREATER than {@code afterId}, ascending and in batches: keyset pagination of the whole journal without
     * paying for the offset. No longer used by {@link AccountBalanceVerifier}, which now aggregates the journal in one
     * SQL statement; currently no caller in src/main.
     */
    List<Posting> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);

    /**
     * Postings that do NOT have a link in the hash-chain yet, in id order, in batches.
     * They are found by ABSENCE from {@code posting_hash} (not by an id watermark): that way a posting whose
     * IDENTITY id is lower but commits AFTER the watermark is never left unchained (it is not skipped).
     */
    @Query("select p from Posting p where not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id) order by p.id asc")
    List<Posting> findUnchainedOrderByIdAsc(Limit limit);

    /**
     * How many postings do NOT have a link in the hash-chain (same definition by ABSENCE as
     * {@link #findUnchainedOrderByIdAsc}). The audit uses it to state which part of the journal it does NOT cover.
     */
    @Query("select count(p) from Posting p where not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id)")
    long countUnchained();

    /** Unlinked postings created BEFORE {@code cutoff}: the ones the chainer should already have covered. */
    @Query("select count(p) from Posting p where p.createdAt < :cutoff and not exists "
            + "(select 1 from PostingHash h where h.postingId = p.id)")
    long countUnchainedCreatedBefore(@Param("cutoff") Instant cutoff);
}
