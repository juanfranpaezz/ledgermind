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
 * Un checkpoint firmado del journal (Signed Tree Head local). Captura la cabeza de la hash-chain en un
 * instante y la firma con ML-DSA. Tamper-EVIDENT a nivel aplicacion: {@code updatable = false} es solo
 * intencion para Hibernate, NO un control de DB. La inmutabilidad real exige WORM / REVOKE UPDATE,DELETE
 * a nivel base de datos; sin eso, un actor con escritura directa en la DB puede reescribir esta fila.
 *
 * <p>Guarda su propia {@code publicKey} por auditabilidad/conveniencia, NO como raiz de confianza:
 * verificar la firma contra esa clave prueba integridad-de-mensaje, no que el firmante fuera autorizado.
 * La clave de confianza debe anclarse fuera de la DB (config/HSM/log de transparencia).
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
        // requerido por JPA
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
