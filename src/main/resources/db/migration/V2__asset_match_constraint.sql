-- =====================================================================================
-- V2 — The asset of a posting must match that of BOTH accounts (an invariant in the DB).
-- Until now this rule only lived in TransferService.apply(); we move it down into the database,
-- consistent with the "invariants in the DB" doctrine (second line of defence).
-- =====================================================================================

-- Needed to be able to reference (id, asset) from a composite FK.
ALTER TABLE account ADD CONSTRAINT account_id_asset_unique UNIQUE (id, asset);

-- Composite FKs: they force posting.asset = the asset of the debited account AND of the credited one.
-- Since both reference the SAME posting.asset, the two accounts end up sharing the asset
-- => structurally impossible to transfer between different currencies.
-- (They subsume V1's simple FKs on debit_account_id / credit_account_id.)
ALTER TABLE posting
    ADD CONSTRAINT posting_debit_account_asset_fk
        FOREIGN KEY (debit_account_id, asset) REFERENCES account (id, asset),
    ADD CONSTRAINT posting_credit_account_asset_fk
        FOREIGN KEY (credit_account_id, asset) REFERENCES account (id, asset);
