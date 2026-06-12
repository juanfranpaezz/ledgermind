package com.ledgermind.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Una cuenta del ledger. Maneja UN solo asset/moneda.
 *
 * <p>El saldo NO se guarda como un numero: se DERIVA de contadores acumulados
 * (estilo TigerBeetle). Los contadores solo se mutan por {@link #applyDebit(long)} /
 * {@link #applyCredit(long)}, nunca por setters publicos: asi la unica forma de mover
 * plata es el camino correcto.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String address;

    @Column(nullable = false, updatable = false, length = 3)
    private String asset;

    @Column(name = "posted_debits", nullable = false)
    private long postedDebits;

    @Column(name = "posted_credits", nullable = false)
    private long postedCredits;

    @Column(name = "pending_debits", nullable = false)
    private long pendingDebits;

    @Column(name = "pending_credits", nullable = false)
    private long pendingCredits;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    /** Optimistic locking: JPA agrega {@code AND version = ?} en cada UPDATE. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // requerido por JPA
    }

    public Account(String address, String asset, boolean allowNegative) {
        this.address = address;
        this.asset = asset;
        this.allowNegative = allowNegative;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Saldo disponible en centavos: lo confirmado menos lo reservado. */
    public long availableBalance() {
        return postedCredits - postedDebits - pendingDebits;
    }

    /** Aplica un debito confirmado (sale plata de esta cuenta). */
    void applyDebit(long amount) {
        this.postedDebits += amount;
    }

    /** Aplica un credito confirmado (entra plata a esta cuenta). */
    void applyCredit(long amount) {
        this.postedCredits += amount;
    }

    public Long getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public String getAsset() {
        return asset;
    }

    public long getPostedDebits() {
        return postedDebits;
    }

    public long getPostedCredits() {
        return postedCredits;
    }

    public long getPendingDebits() {
        return pendingDebits;
    }

    public long getPendingCredits() {
        return pendingCredits;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Identidad por CLAVE NATURAL (address), NO por el id IDENTITY: el id es null antes de
     * persistir y cambia despues, lo que "rompe" la entidad dentro de un Set tras persist/merge.
     * address es unica, inmutable y se asigna en construccion (estrategia de Vlad Mihalcea).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return address != null && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        return address != null ? address.hashCode() : getClass().hashCode();
    }
}
