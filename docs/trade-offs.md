# Trade-offs

Compromises made consciously, why, and what a production rollout would add. This document grows with each increment.

Each compromise has a kind: a **design choice** would stay the same with more time, because it is the right call for this service; a **time constraint** is deferred and has a planned production path.

## Trade-offs

| Area | Kind | Compromise | Why | Production path |
|---|---|---|---|---|
| Database availability | Design choice | A single PostgreSQL instance | Replication and failover are infrastructure concerns, outside the service code | Managed PostgreSQL with synchronous replicas and automatic failover |
| Transport security | Design choice | TLS is not terminated by the service | Keeps the service runnable out of the box, without self-signed certificates | TLS at the gateway or ingress, mTLS between services |
| Token issuance | Design choice | mock-oauth2-server issues tokens for local runs and tests, to anyone who asks | Identity management belongs to a dedicated provider | Point the resource server to the real identity provider |
| Module boundaries | Design choice | Layering enforced by tests, not by the compiler | A single module keeps the build simple | Split into Maven modules if the codebase grows |
| Hot wallets | Design choice | Pessimistic locks serialize operations on the same wallet | Correctness first; contention on a single wallet is rare for end users | Sharded balances or asynchronous posting for high-volume accounts |
| Authentication errors | Design choice | `401` carries the standard `WWW-Authenticate: Bearer` header but no Problem Details body | The bearer token spec already defines the response; a custom entry point adds code for no new information | Problem Details body on `401` and `403`, planned with operability |
| Wallet ids | Design choice | Random UUIDs (v4) as primary keys | The JDK generates them with no extra code | Time-ordered UUIDs (v7) for better index locality at high insert rates |
| Local credentials | Design choice | `compose.yaml` holds a fixed PostgreSQL password | It protects a throwaway database bound to the developer's machine | Credentials from a secret manager, never in the repository |
| Token expiry | Time constraint | Expiry is validated by Spring Security's defaults, without a test of our own | We do not configure it, and an expired token needs extra issuer setup | A test with an expired token if the clock skew is ever tuned |
| Issuer host | Time constraint | Tokens name the issuer by the host the caller used (`localhost:9000`) | Fine while the service runs on the host | When the service joins Docker Compose (increment 7), give the issuer one host name reachable from both sides |
| Stored balance | Design choice | The wallet row keeps the current balance next to the ledger, updated in the same transaction as every entry | Reading a balance stays one row lookup instead of a sum over the ledger; the lock that serializes postings is on that same row | The ledger is the source of truth: a test reconciles the stored balance with the entries; production adds a scheduled reconciliation job that alerts on any drift |
| Retries before idempotency | Time constraint | Until increment 5, a client that retries a deposit or withdrawal after losing the response posts it twice | Idempotency is its own increment, ordered right after transfers | Increment 5 makes `Idempotency-Key` required on postings; that changes the contract, which is accepted before the first release |
| External account | Design choice | The external account keeps no running balance; its entries have no resulting balance | A running balance would be one row locked by every deposit and withdrawal of every user | Its balance is the sum of its entries; settlement with payment providers would reconcile against it |
| Database outage | Time constraint | Readiness does not check the database, a database that is down answers `500` with a generic body, and statements have no timeout | Only the lock wait introduced in this increment is bounded now | With operability (increment 8): database in the readiness group, `503` on connection failures, a statement timeout |
| Busy wallet and the connection pool | Time constraint | Requests waiting for one busy wallet's lock each hold a pooled connection for up to two seconds, so a burst on a single wallet can delay requests for other wallets | The lock wait is bounded and the case needs one user flooding their own wallet | Size the pool for the expected concurrency, and limit concurrent postings per wallet at the gateway or in the service |
| Ledger balance check | Design choice | The deferred check runs once per inserted row, so a two-entry posting sums its entries three times at commit | Each run is an indexed lookup of a few rows; a per-statement check needs triggers PostgreSQL does not allow to be deferred | A single check per transaction id if postings ever carry many entries |
| Request body size | Design choice | The service sets no limit on request body size | The only body is one short field; size limits belong to the gateway, in front of every service | Body size limit at the gateway or ingress |
| Concurrency test timing | Design choice | `LedgerConcurrencyTest` expects all 32 postings on one wallet to clear the two-second lock wait | Each posting holds the lock for a few milliseconds, so 32 of them clear it with a wide margin; a runner slow enough to miss it fails the test rather than hiding a defect | Raise the lock wait for tests only if CI ever shows it |

## Time spent

| Increment | Scope | Hours |
|---|---|---|
| 0–1 | Requirements analysis; project bootstrap: build, quality gates, health probes, CI | 1.0 |
| Planning | Roadmap: increments ordered by user value, branching and definition of done | 0.3 |
| 1 | Create a wallet and retrieve its balance: domain, PostgreSQL persistence, JWT security, HTTP API, stricter architecture rules | 0.7 |
| 2 | Local token issuer and PostgreSQL in Docker Compose, tests against issued tokens, audience validation | 0.4 |
| 3 | Deposits and withdrawals on a double-entry, append-only ledger; row locks with a bounded wait; correlation ids; mutation testing gate | 1.0 |
