-- =====================================================================================
-- V3 — Journal hash-chain (tamper-evidence, AWS QLDB pattern).
--
-- SEPARATE, append-only table: `posting` stays immutable (the hash is NOT added to it
-- with an UPDATE). The chain is computed by an asynchronous job that walks the postings by id
-- and chains them: entry_hash = SHA-256(prev_hash || canonical fields of the posting).
-- Editing an old posting breaks the chain from that point onwards.
-- =====================================================================================
CREATE TABLE posting_hash (
    posting_id   BIGINT       PRIMARY KEY REFERENCES posting (id),
    seq          BIGINT       NOT NULL UNIQUE,        -- position in the chain (chaining order)
    prev_hash    VARCHAR(64)     NOT NULL,               -- hash of the previous link (genesis = 64 zeros)
    entry_hash   VARCHAR(64)     NOT NULL UNIQUE,        -- SHA-256(prev_hash || canonical(posting)), hex
    computed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_hash_seq ON posting_hash (seq);
