# ADR-0001: Optimistic locking + retry para mover dinero

## Estado
Aceptada — 2026-06-12

## Contexto
El saldo de una cuenta se actualiza en cada transferencia. Si dos transferencias
concurrentes tocan la misma cuenta, pueden producir un *lost update*: ambas leen el
mismo saldo, ambas deciden "hay fondos" y una pisa la escritura de la otra. Resultado:
se crea o se pierde dinero. En un ledger eso es inadmisible.

Necesitamos seguridad bajo concurrencia sin sacrificar rendimiento en el caso común,
donde dos operaciones sobre la MISMA cuenta en el MISMO instante son raras.

## Decisión
Usamos **optimistic locking** con `@Version` (JPA) sobre `account`, más un **bucle de
reintento fuera de la transacción** (`TransactionTemplate`, `MAX_ATTEMPTS = 5`):

- Al escribir, JPA emite `UPDATE account SET ..., version = version + 1 WHERE id = ? AND version = ?`.
- Si otra transacción ya cambió la fila, el WHERE matchea 0 filas → `OptimisticLockException`.
- El bucle atrapa esa excepción y reintenta desde cero (re-lee el saldo fresco y re-decide).
- Cada intento es una transacción nueva; por eso el retry vive AFUERA del límite transaccional.

Como segunda línea de defensa, el no-sobregiro también está enforced con un CHECK en la DB.

## Consecuencias
- (+) Sin locks de base de datos ni deadlocks; muy rápido cuando los choques son raros (caso común).
- (+) Convierte una corrupción silenciosa (lost update) en un error ruidoso y atrapable.
- (−) Bajo contención alta sobre una misma fila, hay reintentos (trabajo desperdiciado).
  Medido en el spike: con 50 transferencias concurrentes, 4 agotaron los 5 reintentos.
  Mitigaciones futuras: más reintentos, *backoff* con *jitter*, o serializar por cuenta.

## Alternativas consideradas
- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: bloquea la fila al leer; los demás esperan.
  Sin reintentos, pero serializa el acceso (más lento bajo contención) y arriesga deadlocks.
  Descartada como default; es la alternativa si la contención sobre una cuenta se volviera dominante.
- **Tabla de saldos sin versión**: vulnerable a lost update. Descartada.

## Verificación
`LedgerConcurrencySpikeTest`: 50 transferencias concurrentes contra Postgres real (Testcontainers).
Invariantes verificados: conservación, no-sobregiro, doble-entrada global (Σ créditos − débitos = 0),
un asiento por éxito. Resultado: `ok=10, insufficient=36, conflict=4`.
