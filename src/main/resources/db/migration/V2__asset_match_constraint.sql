-- =====================================================================================
-- V2 — El asset de un asiento debe coincidir con el de AMBAS cuentas (invariante en la DB).
-- Hasta ahora esta regla solo vivia en TransferService.apply(); la subimos a la base,
-- coherente con la doctrina "invariantes en la DB" (segunda linea de defensa).
-- =====================================================================================

-- Necesario para poder referenciar (id, asset) desde una FK compuesta.
ALTER TABLE account ADD CONSTRAINT account_id_asset_unique UNIQUE (id, asset);

-- FKs compuestas: fuerzan que posting.asset = asset de la cuenta debitada Y de la acreditada.
-- Como ambas referencian el MISMO posting.asset, las dos cuentas terminan compartiendo asset
-- => estructuralmente imposible transferir entre monedas distintas.
-- (Subsumen las FKs simples de V1 sobre debit_account_id / credit_account_id.)
ALTER TABLE posting
    ADD CONSTRAINT posting_debit_account_asset_fk
        FOREIGN KEY (debit_account_id, asset) REFERENCES account (id, asset),
    ADD CONSTRAINT posting_credit_account_asset_fk
        FOREIGN KEY (credit_account_id, asset) REFERENCES account (id, asset);
