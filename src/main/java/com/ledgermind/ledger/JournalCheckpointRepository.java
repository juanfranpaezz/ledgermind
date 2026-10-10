package com.ledgermind.ledger;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalCheckpointRepository extends JpaRepository<JournalCheckpoint, Long> {

    /** The latest signed checkpoint. */
    Optional<JournalCheckpoint> findTopByOrderByIdDesc();

    /** The checkpoint that signs the head at {@code chainSeq} (at most one: the column is UNIQUE). */
    Optional<JournalCheckpoint> findByChainSeq(long chainSeq);

    /**
     * Inserts a checkpoint unless one already signs that head. {@code ON CONFLICT (chain_seq) DO NOTHING} instead of
     * a plain INSERT that fails on UNIQUE(chain_seq): in Postgres a failed statement aborts the whole transaction, and
     * Spring marks it rollback-only, so a caller that catches the violation and carries on still dies at commit with
     * {@code UnexpectedRollbackException}. Returns the rows inserted: 1, or 0 if another writer signed this head first.
     * Must run inside a transaction (the checkpoint service's).
     */
    @Modifying
    @Query(value = "INSERT INTO journal_checkpoint (chain_seq, head_hash, algorithm, public_key, signature, signed_at)"
            + " VALUES (:chainSeq, :headHash, :algorithm, :publicKey, :signature, :signedAt)"
            + " ON CONFLICT (chain_seq) DO NOTHING", nativeQuery = true)
    int insertIfHeadNotSigned(@Param("chainSeq") long chainSeq, @Param("headHash") String headHash,
                              @Param("algorithm") String algorithm, @Param("publicKey") String publicKey,
                              @Param("signature") String signature, @Param("signedAt") Instant signedAt);
}
