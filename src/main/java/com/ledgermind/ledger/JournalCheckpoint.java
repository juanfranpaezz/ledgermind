package com.ledgermind.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A signed checkpoint of the journal (local Signed Tree Head). It captures the head of the hash-chain at an
 * instant and signs it with ML-DSA. Tamper-EVIDENT at the application level: {@code updatable = false} is only
 * an intent for Hibernate, NOT a DB control. Real immutability requires WORM / REVOKE UPDATE,DELETE
 * at the database level; without it, an actor with direct write access to the DB can rewrite this row.
 *
 * <p>It stores its own {@code publicKey} for auditability/convenience, NOT as a root of trust:
 * verifying the signature against that key proves message integrity, not that the signer was authorized.
 * The trust key must be anchored outside the DB (config/HSM/transparency log).
 */
@Entity
@Table(name = "journal_checkpoint")
public class JournalCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_seq", nullable = false, updatable = false)
    private long chainSeq;

    @Column(name = "head_hash", nullable = false, updatable = false, length = 64)
    private String headHash;

    @Column(nullable = false, updatable = false, length = 32)
    private String algorithm;

    @Column(name = "public_key", nullable = false, updatable = false, length = 10000)
    private String publicKey;

    @Column(nullable = false, updatable = false, length = 10000)
    private String signature;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private Instant signedAt;

    protected JournalCheckpoint() {
        // required by JPA
    }

    public JournalCheckpoint(long chainSeq, String headHash, String algorithm,
                             String publicKey, String signature) {
        this.chainSeq = chainSeq;
        this.headHash = headHash;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.signature = signature;
    }

    @PrePersist
    void onCreate() {
        this.signedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getChainSeq() {
        return chainSeq;
    }

    public String getHeadHash() {
        return headHash;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getSignature() {
        return signature;
    }

    public Instant getSignedAt() {
        return signedAt;
    }
}
