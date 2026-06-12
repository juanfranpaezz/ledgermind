package com.ledgermind.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Un eslabon de la hash-chain del journal (tamper-evidence). Append-only e inmutable, igual que
 * {@link Posting}. La calcula {@link JournalChainer} de forma asincrona; el posting nunca se toca.
 */
@Entity
@Table(name = "posting_hash")
public class PostingHash {

    @Id
    @Column(name = "posting_id", updatable = false)
    private Long postingId;

    @Column(nullable = false, updatable = false)
    private long seq;

    @Column(name = "prev_hash", nullable = false, updatable = false, length = 64)
    private String prevHash;

    @Column(name = "entry_hash", nullable = false, updatable = false, length = 64)
    private String entryHash;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    protected PostingHash() {
        // requerido por JPA
    }

    public PostingHash(Long postingId, long seq, String prevHash, String entryHash) {
        this.postingId = postingId;
        this.seq = seq;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
    }

    @PrePersist
    void onCreate() {
        this.computedAt = Instant.now();
    }

    public Long getPostingId() {
        return postingId;
    }

    public long getSeq() {
        return seq;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
