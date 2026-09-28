-- Overdraft sweep with a watermark + freeze of the flagged account.
--
-- The posted_debits/posted_credits counters are advanced with += on the write path and NEVER recomputed,
-- so the account_no_overdraft CHECK (V1) looks at the cached number, not at the journal. This sweep re-derives the balance
-- from the journal, but ONLY for the accounts touched by new postings since the last watermark, and freezes
-- the account whose derived balance violates its overdraft rule. The transfer's hot path only adds ONE
-- indexed read (overdraft_flag_one_active_per_account); it re-derives nothing.

-- Sweep watermark: persisted so that a restart does NOT re-sweep or lose its place (single row id = 1).
CREATE TABLE overdraft_sweep_state (
    id                     SMALLINT     PRIMARY KEY CHECK (id = 1),
    watermark_posting_id   BIGINT       NOT NULL DEFAULT 0,   -- every posting with id <= this is already summed
    last_sweep_at          TIMESTAMPTZ,
    last_scanned_from      BIGINT,
    last_scanned_to        BIGINT,
    last_touched_accounts  INT          NOT NULL DEFAULT 0,
    last_flagged           INT          NOT NULL DEFAULT 0,
    last_duration_micros   BIGINT       NOT NULL DEFAULT 0
);
INSERT INTO overdraft_sweep_state (id) VALUES (1);

-- Totals re-derived from the journal per account, up to as_of_posting_id (what makes the sweep incremental).
CREATE TABLE account_derived_total (
    account_id        BIGINT  PRIMARY KEY REFERENCES account(id),
    derived_debits    BIGINT  NOT NULL,
    derived_credits   BIGINT  NOT NULL,
    as_of_posting_id  BIGINT  NOT NULL
);

-- Overdraft flag = freeze. It stores the evidence (derived vs stored, range of postings) and who
-- lifted it and why. Only one ACTIVE flag per account.
CREATE TABLE overdraft_flag (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id         BIGINT        NOT NULL REFERENCES account(id),
    flagged_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    derived_debits     BIGINT        NOT NULL,
    derived_credits    BIGINT        NOT NULL,
    stored_debits      BIGINT        NOT NULL,
    stored_credits     BIGINT        NOT NULL,
    pending_debits     BIGINT        NOT NULL,
    derived_available  BIGINT        NOT NULL,
    stored_available   BIGINT        NOT NULL,
    posting_id_from    BIGINT        NOT NULL,
    posting_id_to      BIGINT        NOT NULL,
    cleared_at         TIMESTAMPTZ,
    cleared_by         VARCHAR(128),
    clear_reason       VARCHAR(512),
    CONSTRAINT overdraft_flag_clear_is_recorded CHECK (
        (cleared_at IS NULL AND cleared_by IS NULL AND clear_reason IS NULL)
        OR (cleared_at IS NOT NULL AND cleared_by IS NOT NULL AND clear_reason IS NOT NULL)
    )
);
CREATE UNIQUE INDEX overdraft_flag_one_active_per_account ON overdraft_flag (account_id) WHERE cleared_at IS NULL;

-- COMMITTED state of the chainer. Every run writes it in the SAME transaction as its
-- links, so the audit reads it in its own snapshot (it used to be an in-memory stamp taken BEFORE the commit).
-- pass_xid = the xid the run takes when it starts (pg_current_xact_id): every HIGHER xid was assigned AFTER the pass
-- (xids are assigned in order). An unlinked posting written by such an xid and with a created_at earlier than
-- run_started_at minus the window cannot be a slow legitimate transaction (that one already had its lower xid when the
-- chainer passed): it is a write outside the app.
CREATE TABLE journal_chainer_state (
    id               SMALLINT     PRIMARY KEY CHECK (id = 1),
    run_started_at   TIMESTAMPTZ  NOT NULL,   -- app clock, taken BEFORE the run's snapshot
    pass_xid         BIGINT       NOT NULL,
    run_finished_at  TIMESTAMPTZ  NOT NULL,   -- app clock, at the end of the run (before the commit)
    chained          INT          NOT NULL,
    hit_batch_limit  BOOLEAN      NOT NULL
);
