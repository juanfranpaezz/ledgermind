# Observability — Micrometer · Prometheus · Grafana

LedgerMind's observability stack. **The app instruments** with Micrometer, **Prometheus** scrapes and stores
the series, **Grafana** plots them. Meant to run **always-on on a VPS** (or locally to take the dashboard screenshot).

## How to run it (all together)

```bash
docker compose -f docker-compose.observability.yml up --build -d
```

- **Grafana** → `http://<host>:3000` — dashboard **"LedgerMind — Observability"** already provisioned (anonymous read-only).
- **Prometheus** → `http://<host>:9090`
- **App / demo** → `http://<host>:8080` — click the demo buttons to generate traffic and watch the panels move.
- **API keys** → the app requires `LEDGERMIND_API_KEYS_FILE` and does not start without it. By default the compose file mounts
  `observability/demo-api-keys.empty` (empty: only the five anonymous `/api/demo/*` endpoints work). To use the rest of
  `/api` with `X-API-Key`, point `LEDGERMIND_API_KEYS_HOST_FILE` at your key file before `up`.

## The pipeline (how it works)

1. **Micrometer** (library in the app): records metrics in a vendor-neutral way. Spring Boot Actuator already instruments
   `http.server.requests` (latency/throughput), the JVM (heap, GC), the DB pool, etc. on its own.
2. The app exposes `GET /actuator/prometheus` (text in Prometheus format). It is enabled by
   `management.endpoints.web.exposure.include: health,prometheus`.
3. **Prometheus** scrapes `app:8080/actuator/prometheus` every 5s (see `prometheus.yml`) and stores the series.
4. **Grafana** queries Prometheus and draws the panels (datasource + dashboard provisioned, see `grafana/`).

## Panels

| Panel | Metric / query | What it shows |
|---|---|---|
| **Concurrency retries** | `ledgermind_transfer_retries` | **Custom metric.** Rises when two transfers collide on the same account (lost optimistic lock) or deadlock (40P01). It is the ledger's **concurrency pressure** — the same counter the concurrency spike asserts is `> 0`. |
| **5xx errors** | `rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[1m])` | Server error rate. |
| **Throughput** | `rate(http_server_requests_seconds_count[1m]) by (uri)` | Requests per second, per endpoint. |
| **Latency p95 / p99** | `histogram_quantile(0.95/0.99, ...)` | HTTP latency percentiles (needs the buckets, enabled with `percentiles-histogram`). |
| **JVM heap** | `jvm_memory_used_bytes{area="heap"}` | Heap memory used. |

## The custom metric

`ledgermind_transfer_retries` is registered in `TransferService` with Micrometer, bound to the same `AtomicLong retries`
that the `catch (ConcurrencyFailureException)` increments. **It is not a coupled test hook**: it is real operational
telemetry of concurrency pressure, and it is also the observable that `LedgerConcurrencySpikeTest` uses to assert
that contention was exercised. It connects the core piece (concurrency) with observability.

## Honest limits

- It is **demo observability**: in-process metrics, no alerting/Alertmanager, no long-term retention,
  no auth on Grafana (anonymous read-only on purpose, to show it). In prod: Grafana with auth, Prometheus with
  retention/remote-write, alert rules, and `/actuator/prometheus` on a **separate management port** or behind auth.
- The images use `:latest` for the demo's simplicity; in prod versions are pinned.
- `/actuator/prometheus` is scraped by Prometheus over the compose **internal network**, but the app serves it on its
  own HTTP port (8080) with **no authentication** (under the demo profile, which this stack and the Render deploy run, the
  default security chain leaves the actuator open; outside demo it needs an `X-API-Key`): anyone who can
  reach that port, including on a public deploy of the app, can read the metrics.

## In three levels of detail

- **L1:** The app is instrumented with Micrometer; Prometheus collects the metrics and Grafana plots them — latency, throughput, errors, and a concurrency-pressure panel.
- **L2:** Actuator exposes `/actuator/prometheus`; Prometheus scrapes every 5s; Grafana has its datasource and dashboard provisioned from files (reproducible, versioned in the repo, zero manual clicks).
- **L3:** The hand-added metric is `ledgermind_transfer_retries`: a gauge bound to the counter of retries on transient conflicts. It is the same signal the concurrency test asserts on — it goes on a dashboard because *contention pressure is exactly what you want to watch on the balance path*. For prod: alerting, auth on Grafana, a separate management port and retention.
