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

## Time spent

| Increment | Scope | Hours |
|---|---|---|
| 0–1 | Requirements analysis; project bootstrap: build, quality gates, health probes, CI | 1.0 |
| Planning | Roadmap: increments ordered by user value, branching and definition of done | 0.3 |
