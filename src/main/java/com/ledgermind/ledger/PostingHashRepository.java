package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingHashRepository extends JpaRepository<PostingHash, Long> {

    /** The head of the chain (the last chained link). */
    Optional<PostingHash> findTopByOrderBySeqDesc();

    /** A single link by its position (to check that the signed head is still present). */
    Optional<PostingHash> findBySeq(long seq);

    /** A page of the chain by seq (keyset/seek pagination) to verify without loading everything into memory. */
    List<PostingHash> findBySeqGreaterThanOrderBySeqAsc(long seq, Limit limit);
}
