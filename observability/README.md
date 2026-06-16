# Observabilidad — Micrometer · Prometheus · Grafana

Stack de observabilidad de LedgerMind. **La app instrumenta** con Micrometer, **Prometheus** scrapea y guarda
las series, **Grafana** las grafica. Pensado para correr **always-on en el VPS** (o local para el screenshot del CV).

## Cómo correr (todo junto)

```bash
docker compose -f docker-compose.observability.yml up --build -d
```

- **Grafana** → `http://<host>:3000` — dashboard **"LedgerMind — Observabilidad"** ya provisionado (anónimo read-only).
- **Prometheus** → `http://<host>:9090`
- **App / demo** → `http://<host>:8080` — tocá los botones del demo para generar tráfico y ver moverse los paneles.

## La cadena (cómo funciona)

1. **Micrometer** (librería en la app): registra métricas de forma neutral. Spring Boot Actuator ya instrumenta solo
   `http.server.requests` (latencia/throughput), JVM (heap, GC), pool de la DB, etc.
2. La app expone `GET /actuator/prometheus` (texto en formato Prometheus). Lo habilita
   `management.endpoints.web.exposure.include: health,prometheus`.
3. **Prometheus** le pega a `app:8080/actuator/prometheus` cada 5s (ver `prometheus.yml`) y guarda las series.
4. **Grafana** consulta a Prometheus y dibuja los paneles (datasource + dashboard provisionados, ver `grafana/`).

## Paneles

| Panel | Métrica / query | Qué muestra |
|---|---|---|
| **Reintentos por concurrencia** | `ledgermind_transfer_retries` | **Métrica custom.** Sube cuando dos transferencias chocan sobre la misma cuenta (optimistic lock perdido) o se deadlockean (40P01). Es la **presión de concurrencia** del ledger — el mismo contador que el spike de concurrencia afirma `> 0`. |
| **Errores 5xx** | `rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[1m])` | Tasa de errores de servidor. |
| **Throughput** | `rate(http_server_requests_seconds_count[1m]) by (uri)` | Requests por segundo, por endpoint. |
| **Latencia p95 / p99** | `histogram_quantile(0.95/0.99, ...)` | Percentiles de latencia HTTP (requiere los buckets, habilitados con `percentiles-histogram`). |
| **JVM heap** | `jvm_memory_used_bytes{area="heap"}` | Memoria heap usada. |

## La métrica custom (lo que más vale defender)

`ledgermind_transfer_retries` se registra en `TransferService` con Micrometer, atada al mismo `AtomicLong retries`
que incrementa el `catch (ConcurrencyFailureException)`. **No es un hook de test acoplado**: es telemetría operativa
real de la presión de concurrencia, y de paso es el observable que el `LedgerConcurrencySpikeTest` usa para afirmar
que la contención se ejercitó. Conecta la pieza estrella (concurrencia) con la observabilidad.

## Honestidad (anti-overclaim)

- Es **observabilidad de demo**: métricas in-process, sin alerting/Alertmanager, sin retención de largo plazo,
  sin auth en Grafana (anónimo read-only a propósito, para mostrarlo). En prod: Grafana con auth, Prometheus con
  retención/remote-write, reglas de alerta, y el `/actuator/prometheus` en un **management port aparte** o detrás de auth.
- Las imágenes usan `:latest` por simplicidad del demo; en prod se pinnean versiones.
- El endpoint `/actuator/prometheus` lo consume Prometheus por la **red interna** del compose; no se publica al internet.

## Defensa de entrevista (3 niveles)

- **N1:** "Instrumenté la app con Micrometer; Prometheus junta las métricas y Grafana las grafica — latencia, throughput, errores, y un panel de presión de concurrencia."
- **N2:** "Actuator expone `/actuator/prometheus`; Prometheus scrapea cada 5s; Grafana tiene datasource y dashboard provisionados por archivo (reproducible, versionado en el repo, cero click manual)."
- **N3:** "La métrica que agregué a mano es `ledgermind_transfer_retries`: un gauge atado al contador de reintentos por conflicto transitorio. Es la misma señal que mi test de concurrencia asercióna — la subo a un dashboard porque *la presión de contención es justamente lo que querés vigilar en el path de saldo*. Para prod: alerting, auth en Grafana, management port separado y retención."
