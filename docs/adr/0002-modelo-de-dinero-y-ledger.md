# ADR-0002: Dinero como enteros y ledger inmutable de doble entrada

## Estado
Aceptada — 2026-06-12

## Contexto
El sistema mueve dinero. Necesitamos representarlo sin errores de redondeo, poder auditar
cada movimiento, y que sea estructuralmente imposible que el dinero "desaparezca" o se duplique.

## Decisión
1. **Dinero como enteros en minor units (centavos), `BIGINT`/`long`** — nunca `float`/`double`.
2. **Ledger de doble entrada**: cada movimiento es un asiento (`posting`) con una cuenta debitada
   y una acreditada por el mismo importe. La suma global de (créditos − débitos) es siempre 0.
3. **Asientos inmutables (append-only)**: la tabla `posting` solo admite INSERT. Una corrección
   es un asiento nuevo invertido (storno), nunca UPDATE/DELETE.
4. **Saldo derivado**: la cuenta guarda contadores acumulados (`posted_debits`, `posted_credits`,
   más `pending_*` para two-phase); el saldo se calcula como `créditos − débitos` (O(1)). No hay un
   campo `balance` mutable que se pueda pisar.
5. **Direcciones de cuenta jerárquicas** (`wallet:user:x`, `mp:settlement`, `external:funding`) y
   **un asset por cuenta** (nunca se mezclan monedas en un mismo saldo).

## Consecuencias
- (+) Exactitud al centavo; auditabilidad total (historial completo e inmutable).
- (+) Imposible crear/perder dinero: el invariante de suma-cero es estructural y, en parte, enforced en la DB.
- (+) El saldo es O(1) y el journal alimenta naturalmente un read-model (futuro MCP de auditoría).
- (−) Más escrituras: cada transferencia actualiza los contadores de dos cuentas (lo que motiva el [ADR-0001](0001-optimistic-locking.md)).
- (−) El fondeo necesita una cuenta externa que puede ir a negativo (`allow_negative`), contrapartida
  contable del dinero real (se concilia contra el PSP — futuro módulo de reconciliación).

## Alternativas consideradas
- **`BigDecimal`**: válido y exacto; elegimos enteros en centavos por simplicidad y performance,
  con `BigDecimal` como opción equivalente. Lo que se descarta es `double`/`float`.
- **Single-entry (tabla de saldos)**: más simple pero indebuggeable y sin auditoría. Descartada.
- **DSL de movimientos (tipo Numscript de Formance)**: over-engineering para este alcance. Descartada
  a conciencia; en Java el "DSL" es un servicio de dominio tipado.

## Referencias
Modelo inspirado en TigerBeetle (cuentas + transfers inmutables, contadores de saldo) y en el canon
contable de doble entrada.
