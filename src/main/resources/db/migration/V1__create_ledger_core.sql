-- =====================================================================================
-- V1 — Núcleo del ledger de doble entrada.
--
-- Principios de diseño (cada uno con su porqué):
--   1. Dinero como ENTEROS en minor units (centavos). NUNCA float/double.
--   2. Asientos INMUTABLES (append-only): nunca UPDATE ni DELETE sobre `posting`.
--      Una corrección es un asiento NUEVO con las cuentas invertidas (storno).
--   3. El saldo NO se guarda como un número con signo: se DERIVA de contadores
--      acumulados (estilo TigerBeetle). Anti-drift y anti-bug-de-signo.
--   4. Idempotencia a nivel de asiento via UNIQUE constraint.
--   5. Invariantes de dinero enforced EN LA BASE DE DATOS (segunda línea de defensa),
--      no solo en la app.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- account: una cuenta del ledger. Cada cuenta maneja UN solo asset/moneda.
-- -------------------------------------------------------------------------------------
CREATE TABLE account (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Dirección jerárquica legible (estilo plan de cuentas), en vez de UUIDs opacos.
    -- Ej: 'wallet:user:juan', 'mp:settlement', 'external:funding'. Da namespacing gratis.
    address          VARCHAR(128) NOT NULL UNIQUE,

    -- Un account = un solo asset. NUNCA se suman monedas distintas en el mismo saldo.
    asset            VARCHAR(3)      NOT NULL,

    -- Contadores acumulados en centavos. Monótonos crecientes (nunca decrecen).
    -- 'posted'  = movimientos confirmados.
    -- 'pending' = movimientos reservados pero no confirmados (two-phase / holds, ej.
    --             una autorización de tarjeta). Por ahora quedan en 0; se usan más adelante.
    posted_debits    BIGINT       NOT NULL DEFAULT 0,
    posted_credits   BIGINT       NOT NULL DEFAULT 0,
    pending_debits   BIGINT       NOT NULL DEFAULT 0,
    pending_credits  BIGINT       NOT NULL DEFAULT 0,

    -- Algunas cuentas (la fuente externa de fondos) PUEDEN quedar en negativo: son la
    -- contrapartida contable del dinero que entra al sistema. Las wallets de usuario NO.
    allow_negative   BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Optimistic locking de JPA (@Version): detecta y rechaza escrituras concurrentes perdidas.
    version          BIGINT       NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Los contadores jamás pueden ser negativos.
    CONSTRAINT account_counters_non_negative CHECK (
        posted_debits  >= 0 AND posted_credits  >= 0 AND
        pending_debits >= 0 AND pending_credits >= 0
    ),

    -- INVARIANTE DE NO-SOBREGIRO (la pieza clave de seguridad bajo concurrencia):
    -- saldo disponible = posted_credits - posted_debits - pending_debits.
    -- Para una wallet (credit-normal) nunca puede ser negativo. La fuente externa se exime.
    -- Aunque la app tuviera un bug de carrera, esta CHECK impide crear dinero de la nada.
    CONSTRAINT account_no_overdraft CHECK (
        allow_negative OR (posted_credits - posted_debits - pending_debits >= 0)
    )
);

-- -------------------------------------------------------------------------------------
-- posting: el journal. Cada fila es UN asiento de doble entrada (un débito y un crédito
-- por el mismo importe). Tabla INMUTABLE: solo INSERT. Nunca UPDATE/DELETE.
-- -------------------------------------------------------------------------------------
CREATE TABLE posting (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    debit_account_id   BIGINT       NOT NULL REFERENCES account(id),
    credit_account_id  BIGINT       NOT NULL REFERENCES account(id),
    amount             BIGINT       NOT NULL,
    asset              VARCHAR(3)      NOT NULL,

    -- Idempotencia: el mismo idempotency_key no puede generar dos asientos.
    -- Si llega un reintento con la misma clave, el INSERT choca contra esta UNIQUE.
    idempotency_key    VARCHAR(64)  NOT NULL UNIQUE,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- El importe siempre es positivo; la dirección la dan las dos cuentas.
    CONSTRAINT posting_amount_positive   CHECK (amount > 0),
    -- Un asiento no puede debitar y acreditar la misma cuenta (sería un no-op).
    CONSTRAINT posting_distinct_accounts CHECK (debit_account_id <> credit_account_id)
);

-- Índices para reconstruir el historial / saldo de una cuenta (lo usará el read-model del MCP).
CREATE INDEX idx_posting_debit_account  ON posting (debit_account_id);
CREATE INDEX idx_posting_credit_account ON posting (credit_account_id);
