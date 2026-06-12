# LedgerMind

> Core de pagos en Java/Spring Boot con **ledger de doble entrada inmutable**, **idempotencia de grado fintech** y (próximamente) un **servidor MCP** para auditoría asistida por IA.
>
> *A payments core in Java/Spring Boot with an immutable double-entry ledger, fintech-grade idempotency and (soon) an MCP server for AI-assisted, read-only auditing.*

## Qué es
LedgerMind es el motor de cuentas y movimientos de una fintech: registra dinero como asientos de
doble entrada inmutables, garantiza que un reintento no duplique un cobro (idempotencia) y que el
dinero no se cree ni se pierda bajo concurrencia. Es backend puro — la parte que valoran los roles
de *backend engineer*.

## Stack
- **Java 21**, **Spring Boot 3.5**, **Spring Modulith** (monolito modular)
- **PostgreSQL** + **Flyway** (el esquema lo manda Flyway; Hibernate solo valida)
- **Testcontainers** (tests contra Postgres real, no H2)
- Observabilidad: Actuator + Micrometer/Prometheus
- *Roadmap:* **Spring AI 1.1** (servidor MCP read-only + OAuth2.1) para auditoría con un agente

## Cómo correr
```bash
# Correr la app localmente
docker compose up -d            # Postgres
./mvnw spring-boot:run

# Tests (incluye el spike de concurrencia). Solo necesita Docker corriendo:
./mvnw test                     # levanta un Postgres efímero con Testcontainers
```

## Estado
**Semana 1 ✅** — núcleo del ledger (cuentas, asientos, transferencias), idempotencia, y el
**spike de concurrencia**: 50 transferencias en paralelo sobre una cuenta con saldo limitado,
verificando que el dinero se conserva (conservación, no-sobregiro, doble-entrada global).

## Diseño y decisiones
Las decisiones técnicas formales están en [`docs/adr/`](docs/adr):
- [ADR-0001](docs/adr/0001-optimistic-locking.md) — Optimistic locking + retry para mover dinero.
- [ADR-0002](docs/adr/0002-modelo-de-dinero-y-ledger.md) — Dinero como enteros y ledger inmutable de doble entrada.

## English summary
LedgerMind is a payments backend: an immutable double-entry ledger with exact integer money,
exactly-once idempotency, and explicit concurrency control (optimistic locking + retry), proven by
a Testcontainers concurrency test. A read-only MCP server (Spring AI) for AI-assisted auditing is on
the roadmap. See [`docs/adr/`](docs/adr) for the rationale behind each decision.
