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
 * Un asiento de doble entrada: un debito y un credito por el mismo importe.
 *
 * <p>INMUTABLE: no tiene setters y sus columnas son {@code updatable = false}.
 * Una correccion es un asiento NUEVO con las cuentas invertidas (storno), nunca un UPDATE.
 * Referencia las cuentas por id (no {@code @ManyToOne}): un asiento es un hecho liviano.
 */
@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "debit_account_id", nullable = false, updatable = false)
    private Long debitAccountId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private Long creditAccountId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String asset;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
        // requerido por JPA
    }

    public Posting(Long debitAccountId, Long creditAccountId, long amount, String asset, String idempotencyKey) {
        this.debitAccountId = debitAccountId;
        this.creditAccountId = creditAccountId;
        this.amount = amount;
        this.asset = asset;
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDebitAccountId() {
        return debitAccountId;
    }

    public Long getCreditAccountId() {
        return creditAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public String getAsset() {
        return asset;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Identidad por CLAVE NATURAL (idempotency_key): unica, inmutable y asignada en construccion.
     * No usamos el id IDENTITY (null antes de persistir, cambia despues).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Posting other)) {
            return false;
        }
        return idempotencyKey != null && idempotencyKey.equals(other.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return idempotencyKey != null ? idempotencyKey.hashCode() : getClass().hashCode();
    }
}
