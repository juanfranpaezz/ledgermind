-- =====================================================================================
-- V1 — Core of the double-entry ledger.
--
-- Design principles (each one with its reason):
--   1. Money as INTEGERS in minor units (cents). NEVER float/double.
--   2. IMMUTABLE postings (append-only): never UPDATE or DELETE on `posting`.
--      A correction is a NEW posting with the accounts reversed (storno).
--   3. The balance is NOT stored as a signed number: it is DERIVED from accumulated
--      counters (TigerBeetle style). Anti-drift and anti-sign-bug.
--   4. Posting-level idempotency via a UNIQUE constraint.
--   5. Money invariants enforced IN THE DATABASE (second line of defence),
--      not only in the app.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- account: a ledger account. Every account holds ONE single asset/currency.
-- -------------------------------------------------------------------------------------
CREATE TABLE account (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Readable hierarchical address (chart-of-accounts style), instead of opaque UUIDs.
    -- E.g.: 'wallet:user:juan', 'mp:settlement', 'external:funding'. It gives namespacing for free.
    address          VARCHAR(128) NOT NULL UNIQUE,

    -- One account = one single asset. Different currencies are NEVER added into the same balance.
    asset            VARCHAR(3)      NOT NULL,

    -- Accumulated counters in cents. Monotonically increasing (they never decrease).
    -- 'posted'  = confirmed movements.
    -- 'pending' = movements reserved but not confirmed (two-phase / holds, e.g.
    --             a card authorization). For now they stay at 0; they are used later.
    posted_debits    BIGINT       NOT NULL DEFAULT 0,
    posted_credits   BIGINT       NOT NULL DEFAULT 0,
    pending_debits   BIGINT       NOT NULL DEFAULT 0,
    pending_credits  BIGINT       NOT NULL DEFAULT 0,

    -- Some accounts (the external source of funds) CAN go negative: they are the
    -- accounting counterpart of the money that enters the system. User wallets can NOT.
    allow_negative   BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Optimistic locking de JPA (@Version): detecta y rechaza escrituras concurrentes perdidas.
    version          BIGINT       NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The counters can never be negative.
    CONSTRAINT account_counters_non_negative CHECK (
        posted_debits  >= 0 AND posted_credits  >= 0 AND
        pending_debits >= 0 AND pending_credits >= 0
    ),

    -- NO-OVERDRAFT INVARIANT (the key safety piece under concurrency):
    -- available balance = posted_credits - posted_debits - pending_debits.
    -- For a wallet (credit-normal) it can never be negative. The external source is exempt.
    -- Even if the app had a race bug, this CHECK prevents creating money out of thin air.
    CONSTRAINT account_no_overdraft CHECK (
        allow_negative OR (posted_credits - posted_debits - pending_debits >= 0)
    )
);

-- -------------------------------------------------------------------------------------
-- posting: the journal. Every row is ONE double-entry posting (one debit and one credit
-- for the same amount). IMMUTABLE table: INSERT only. Never UPDATE/DELETE.
-- -------------------------------------------------------------------------------------
CREATE TABLE posting (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    debit_account_id   BIGINT       NOT NULL REFERENCES account(id),
    credit_account_id  BIGINT       NOT NULL REFERENCES account(id),
    amount             BIGINT       NOT NULL,
    asset              VARCHAR(3)      NOT NULL,

    -- Idempotency: the same idempotency_key cannot generate two postings.
    -- If a retry arrives with the same key, the INSERT hits this UNIQUE.
    idempotency_key    VARCHAR(64)  NOT NULL UNIQUE,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The amount is always positive; the direction is given by the two accounts.
    CONSTRAINT posting_amount_positive   CHECK (amount > 0),
    -- A posting cannot debit and credit the same account (it would be a no-op).
    CONSTRAINT posting_distinct_accounts CHECK (debit_account_id <> credit_account_id)
);

-- Indexes to rebuild an account's history / balance (the MCP read-model will use them).
CREATE INDEX idx_posting_debit_account  ON posting (debit_account_id);
CREATE INDEX idx_posting_credit_account ON posting (credit_account_id);
