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
| Stored balance | Time constraint | The balance lives on the wallet row before the ledger exists | It is the minimum that serves FR2 in this increment | The ledger becomes the source of truth and the stored balance is reconciled against it |

## Time spent

| Increment | Scope | Hours |
|---|---|---|
| 0–1 | Requirements analysis; project bootstrap: build, quality gates, health probes, CI | 1.0 |
| Planning | Roadmap: increments ordered by user value, branching and definition of done | 0.3 |
| 1 | Create a wallet and retrieve its balance: domain, PostgreSQL persistence, JWT security, HTTP API, stricter architecture rules | 0.7 |
| 2 | Local token issuer and PostgreSQL in Docker Compose, tests against issued tokens, audience validation | 0.4 |
