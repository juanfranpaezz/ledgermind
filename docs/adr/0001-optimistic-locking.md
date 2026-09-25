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

El `catch` no atrapa solo el conflicto optimista: atrapa `ConcurrencyFailureException`, la
super-clase común que cubre **ambos** casos reintentables. Además del optimista por `@Version`
(`ObjectOptimisticLockingFailureException`), eso incluye el **deadlock de Postgres (`40P01`)** que
ocurre cuando dos transferencias opuestas (A→B y B→A) toman los locks de fila en orden inverso.
Antes el deadlock escapaba como un 500 pese a ser perfectamente reintentable; ahora cae en el mismo
bucle de retry. Para reducir el deadlock *de raíz* (no solo absorberlo), `hibernate.order_updates` /
`order_inserts` ordenan las escrituras por id, así ambas transferencias toman los locks en el mismo
orden y el choque baja de frecuencia; el retry cubre el residual. *(La verdad está en
`TransferService.java`, no en este ADR: ver el `catch (ConcurrencyFailureException)`, líneas ~64-71.)*

Como segunda línea de defensa, el no-sobregiro también está enforced con un CHECK en la DB.

## Consecuencias
- (+) Sin locks pesimistas de base de datos (`SELECT ... FOR UPDATE`); muy rápido cuando los choques
  son raros (caso común).
- (+) Convierte una corrupción silenciosa (lost update) en un error ruidoso y atrapable.
- (~) **Los deadlocks SÍ pueden ocurrir** (dos transferencias opuestas que lockean filas en orden
  inverso → `40P01`). No los evitamos por completo: los **reducimos** ordenando las escrituras por id
  (`hibernate.order_updates`/`order_inserts`) y **absorbemos** el residual en el mismo bucle de retry,
  que trata el deadlock como un conflicto transitorio reintentable más. *(Corrección respecto de una
  versión anterior de este ADR que afirmaba "sin deadlocks": el código real los maneja explícitamente
  en `TransferService.java`; el código es la verdad.)*
- (−) Bajo contención alta sobre una misma fila, hay reintentos (trabajo desperdiciado).
  En el spike de 50 transferencias concurrentes, algunas agotan los 5 reintentos y fallan con 409
  *aunque la causa es contención, no falta de fondos* (ver la cifra exacta de una corrida en
  Verificación, abajo, y la advertencia de que es un dato de UNA corrida).
  Mitigaciones aplicadas: *backoff* exponencial acotado + *jitter* (ya en el código). Mitigaciones
  futuras posibles: más reintentos o serializar por cuenta.

## Alternativas consideradas
- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: bloquea la fila al leer; los demás esperan.
  Sin reintentos, pero serializa el acceso (más lento bajo contención) y arriesga deadlocks.
  Descartada como default; es la alternativa si la contención sobre una cuenta se volviera dominante.
- **Tabla de saldos sin versión**: vulnerable a lost update. Descartada.

## Verificación
`LedgerConcurrencySpikeTest`: 50 transferencias concurrentes contra Postgres real (Testcontainers).
Invariantes verificados: conservación, no-sobregiro, doble-entrada global (Σ créditos − débitos = 0),
un asiento por éxito, y **que hubo al menos un reintento** (si no, el scheduler pudo correr los hilos
casi en serie y el test pasaría sin ejercitar la concurrencia — falsa cobertura).

> **Nota sobre los números.** El reparto `ok=10, insufficient=36, conflict=4` es el resultado de **UNA
> corrida puntual**, registrado antes de agregar el *backoff* exponencial + *jitter*. **No es un
> invariante garantizado** y el test, a propósito, **no** lo afirma exacto: solo afirma rangos (éxitos
> entre 1 y 10, según el saldo) y que hubo retries. Con el *backoff*+*jitter* ya en el código, es
> esperable que **menos** transferencias agoten los reintentos, así que el `conflict=4` puede estar
> desactualizado. Tomalo como ilustración de "bajo contención alta algunas agotan los reintentos", no
> como una cifra fija.
