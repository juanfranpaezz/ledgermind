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
 * A ledger account. It holds ONE single asset/currency.
 *
 * <p>The balance is NOT stored as a number: it is DERIVED from accumulated counters
 * (TigerBeetle style). The counters are only mutated through {@link #applyDebit(long)} /
 * {@link #applyCredit(long)}, never through public setters: that way the only way to move
 * money is the correct path.
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

    /** Optimistic locking: JPA adds {@code AND version = ?} to every UPDATE. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // required by JPA
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

    /**
     * Available balance in cents: what is posted minus what is reserved.
     *
     * <p>Holds (two-phase reservations) are NOT implemented: no code path writes {@code pending_debits} or
     * {@code pending_credits}, so both are always 0 and this equals {@code postedCredits - postedDebits}. The
     * columns, the {@code pendingDebits} term here and in the overdraft sweep are reserved for a future hold
     * flow (pinned by PendingCountersAndConservationTest).
     */
    public long availableBalance() {
        return postedCredits - postedDebits - pendingDebits;
    }

    /** Applies a posted debit (money leaves this account). */
    void applyDebit(long amount) {
        this.postedDebits = addExact(postedDebits, amount, "posted_debits");
    }

    /** Applies a posted credit (money enters this account). */
    void applyCredit(long amount) {
        this.postedCredits = addExact(postedCredits, amount, "posted_credits");
    }

    /** Exact counter arithmetic: past Long.MAX the write is rejected (422), never wrapped into a negative counter. */
    private long addExact(long counter, long amount, String column) {
        try {
            return Math.addExact(counter, amount);
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("The transfer would take " + column + " of account '" + address
                    + "' beyond the 64-bit range; nothing was written.");
        }
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
     * Identity by NATURAL KEY (address), NOT by the IDENTITY id: the id is null before
     * persisting and changes afterwards, which "breaks" the entity inside a Set after persist/merge.
     * address is unique, immutable and assigned at construction (Vlad Mihalcea's strategy).
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
