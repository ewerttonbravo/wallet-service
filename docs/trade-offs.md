# Trade-offs

Compromises made consciously, why, and what a production rollout would add. This document grows with each increment.

## Trade-offs

| Area | Compromise | Why | Production path |
|---|---|---|---|
| Database availability | A single PostgreSQL instance | Replication and failover are infrastructure concerns, outside the service code | Managed PostgreSQL with synchronous replicas and automatic failover |
| Transport security | TLS is not terminated by the service | Keeps the service runnable out of the box, without self-signed certificates | TLS at the gateway or ingress, mTLS between services |
| Token issuance | A mock issuer for local runs | Identity management belongs to a dedicated provider | Point the resource server to the real identity provider |
| Module boundaries | Layering enforced by tests, not by the compiler | A single module keeps the build simple | Split into Maven modules if the codebase grows |
| Hot wallets | Pessimistic locks serialize operations on the same wallet | Correctness first; contention on a single wallet is rare for end users | Sharded balances or asynchronous posting for high-volume accounts |
| Authentication errors | `401` carries the standard `WWW-Authenticate: Bearer` header but no Problem Details body | The bearer token spec already defines the response; a custom entry point adds code for no new information | Problem Details body on `401` and `403`, planned with operability |
| Token audience | The `aud` claim is not validated yet | There is no real issuer before the local one arrives | Set `spring.security.oauth2.resourceserver.jwt.audiences` to the service's audience |
| Wallet ids | Random UUIDs (v4) as primary keys | The JDK generates them with no extra code | Time-ordered UUIDs (v7) for better index locality at high insert rates |
| Stored balance | The balance lives on the wallet row before the ledger exists | It is the minimum that serves FR2 in this increment | The ledger becomes the source of truth and the stored balance is reconciled against it |

## Time spent

| Increment | Scope | Hours |
|---|---|---|
| 0–1 | Requirements analysis; project bootstrap: build, quality gates, health probes, CI | 1.0 |
| Planning | Roadmap: increments ordered by user value, branching and definition of done | 0.3 |
| 1 | Create a wallet and retrieve its balance: domain, PostgreSQL persistence, JWT security, HTTP API, stricter architecture rules | 0.7 |
