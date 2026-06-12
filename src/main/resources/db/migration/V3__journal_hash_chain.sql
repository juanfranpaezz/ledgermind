-- =====================================================================================
-- V3 — Hash-chain del journal (tamper-evidence, patron AWS QLDB).
--
-- Tabla SEPARADA y append-only: el `posting` sigue siendo inmutable (NO se le agrega el
-- hash con un UPDATE). La cadena la calcula un job asincrono que recorre los asientos por id
-- y encadena: entry_hash = SHA-256(prev_hash || campos canonicos del asiento).
-- Editar un asiento viejo rompe la cadena desde ese punto en adelante.
-- =====================================================================================
CREATE TABLE posting_hash (
    posting_id   BIGINT       PRIMARY KEY REFERENCES posting (id),
    seq          BIGINT       NOT NULL UNIQUE,        -- posicion en la cadena (orden de encadenado)
    prev_hash    VARCHAR(64)     NOT NULL,               -- hash del eslabon anterior (genesis = 64 ceros)
    entry_hash   VARCHAR(64)     NOT NULL UNIQUE,        -- SHA-256(prev_hash || canonical(posting)), hex
    computed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_hash_seq ON posting_hash (seq);
