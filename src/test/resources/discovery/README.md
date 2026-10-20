# Real Nexora Discovery responses (test fixtures)

These files are **unmodified** `200 OK` bodies of `POST /api/discovery` produced by the Nexora Discovery
`0.1.0-SNAPSHOT` jar (built 2026-10-19, Spring Boot 3.3.5), used by Semantic's S2 tests. They contain no credentials
or host names (Discovery never returns connection data).

## How they were captured (2026-10-19)

1. A disposable `postgres:17.6` container was initialised with Discovery's own scripts
   (`Nexora Discovery/docker/postgres/init`, mounted read-only): schemas `inventory`, `sales`, `restricted`, and the
   least-privilege role `nexora_discovery` (USAGE on `inventory` and `sales` only).
2. The prebuilt Discovery jar was run unchanged from a scratch directory, with its target policy limited to
   `127.0.0.1/32` and the container port. Discovery's repository was not modified or rebuilt.
3. Requests (engine `postgresql`, database `erp`):

| File | Request `schemas` | Notes |
|---|---|---|
| `erp-all-schemas.json` | (all) | `inventory` + `sales`; `restricted` silently omitted (no USAGE) |
| `erp-sales.json` | `["sales"]` | |
| `erp-sales-restricted.json` | `["sales","restricted"]` | `restricted` silently omitted |
| `erp-all-schemas-v2.json` | (all) | after: `ADD COLUMN sales.customers.segment varchar(20)`; `inventory.products.name` → `varchar(200)`; `COMMENT ON TABLE sales.orders`; `CREATE TABLE sales.refunds (id, order_id → orders, amount)`; `DROP VIEW sales.v_order_summary` |

The container and the Discovery process were removed afterwards.
