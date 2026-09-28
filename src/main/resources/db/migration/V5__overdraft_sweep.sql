-- Barrido de sobregiro con marca de agua + congelamiento de la cuenta marcada (dec-151, 2026-09-24).
--
-- Los contadores posted_debits/posted_credits se adelantan con += en el camino de escritura y NUNCA se recomputan,
-- asi que el CHECK account_no_overdraft (V1) mira el numero cacheado, no el journal. Este barrido re-deriva el saldo
-- desde el journal, pero SOLO para las cuentas que tocaron asientos nuevos desde la ultima marca de agua, y congela
-- la cuenta cuyo saldo derivado viola su regla de sobregiro. El camino caliente de la transferencia solo agrega UNA
-- lectura indexada (overdraft_flag_one_active_per_account); no re-deriva nada.

-- Marca de agua del barrido: persistida para que un reinicio NO re-barra ni pierda el punto (fila unica id = 1).
CREATE TABLE overdraft_sweep_state (
    id                     SMALLINT     PRIMARY KEY CHECK (id = 1),
    watermark_posting_id   BIGINT       NOT NULL DEFAULT 0,   -- todos los asientos con id <= esto ya estan sumados
    last_sweep_at          TIMESTAMPTZ,
    last_scanned_from      BIGINT,
    last_scanned_to        BIGINT,
    last_touched_accounts  INT          NOT NULL DEFAULT 0,
    last_flagged           INT          NOT NULL DEFAULT 0,
    last_duration_micros   BIGINT       NOT NULL DEFAULT 0
);
INSERT INTO overdraft_sweep_state (id) VALUES (1);

-- Totales re-derivados del journal por cuenta, hasta as_of_posting_id (lo que hace incremental al barrido).
CREATE TABLE account_derived_total (
    account_id        BIGINT  PRIMARY KEY REFERENCES account(id),
    derived_debits    BIGINT  NOT NULL,
    derived_credits   BIGINT  NOT NULL,
    as_of_posting_id  BIGINT  NOT NULL
);

-- Marca de sobregiro = congelamiento. Guarda la evidencia (derivado vs guardado, rango de asientos) y quien la
-- levanto y por que. Una sola marca ACTIVA por cuenta.
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

-- Estado COMMITEADO del encadenador (A4 del gate, 2026-09-24). Cada corrida lo escribe en la MISMA transaccion que sus
-- eslabones, asi la auditoria lo lee en su misma foto (antes era un sello en memoria tomado ANTES del commit).
-- pass_xid = el xid que la corrida toma al empezar (pg_current_xact_id): todo xid MAYOR se asigno DESPUES de la pasada
-- (los xid se asignan en orden). Un asiento sin eslabon escrito por un xid asi y con created_at anterior a
-- run_started_at menos la ventana no puede ser una transaccion legitima lenta (esa ya tenia su xid, menor, cuando paso
-- el encadenador): es escritura por fuera de la app.
CREATE TABLE journal_chainer_state (
    id               SMALLINT     PRIMARY KEY CHECK (id = 1),
    run_started_at   TIMESTAMPTZ  NOT NULL,   -- reloj de la app, tomado ANTES de la foto de la corrida
    pass_xid         BIGINT       NOT NULL,
    run_finished_at  TIMESTAMPTZ  NOT NULL,   -- reloj de la app, al final de la corrida (antes del commit)
    chained          INT          NOT NULL,
    hit_batch_limit  BOOLEAN      NOT NULL
);
