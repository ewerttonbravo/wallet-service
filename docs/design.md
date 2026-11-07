# Design

How the Wallet Service is built and how it meets the [requirements](requirements.md). This document grows with each increment.

## Architecture

The service follows a hexagonal (ports and adapters) layout inside a single Maven module, under the base package `com.ebravo.wallet`:

| Package | Responsibility | May depend on |
|---|---|---|
| `domain` | Wallets, ledger, money and the business rules around them | The JDK only |
| `application` | Use cases that orchestrate the domain through ports | `domain` |
| `adapter` | HTTP, persistence, security and other technology | `application`, `domain` |

Dependencies point inward: technology depends on the domain, never the other way around. The rules are enforced by `ArchitectureTest`, so a violation fails the build instead of waiting for code review.

## Technology

- Java 25, Spring Boot 4.1.
- Maven through the Maven Wrapper.
- JUnit 5, AssertJ, ArchUnit, JaCoCo.
- GitHub Actions runs `./mvnw verify` on every push and pull request.

## Decisions

| ID | Decision | Rationale |
|---|---|---|
| D1 | One wallet per user, single currency (BRL) | Multi-currency adds conversion and per-currency balances without serving any stated requirement. |
| D2 | Double-entry ledger with a system external account | Deposits debit the external account and withdrawals credit it, so every transaction balances to zero. Money can't appear or disappear without a record, which is the strongest audit guarantee. |
| D3 | Pessimistic locking | Under contention on the same wallet it is simpler and more predictable than optimistic locking with retries. |
| D4 | Mandatory idempotency keys | Clients of a mission-critical service retry on timeouts; without idempotency a retry can move money twice. |
| D5 | PostgreSQL, run through Docker | Row-level locking and concurrent writers are required by D3 and NFR4. Embedded databases such as SQLite lock the whole database on writes and can't be shared by multiple instances. |
| D6 | Paginated transaction listing | Low cost, and it makes the audit trail visible through the API. |
| D7 | JWT validated in the service, TLS at the infrastructure | Stateless token validation scales with the service. Token issuance belongs to an identity provider; a mock issuer covers local runs. In-process TLS would require self-signed certificates and hurt the out-of-the-box experience. |
| D8 | UTC storage, ISO-8601 with offset at the API | Ledger timestamps must be unambiguous; time zones are a presentation concern, and this API has no presentation layer. |
| D9 | Single Maven module, layering enforced by ArchUnit | Keeps the build simple. Multi-module would enforce the same boundaries at compile time, at the cost of more build configuration for a service of this size. |
| D10 | 100% line and branch coverage enforced by the build | Every line handling money is exercised by a test. Only the bootstrap class (`*Application`) is excluded; any new exclusion must be justified in `pom.xml`. |
| D11 | Health, liveness and readiness probes from day one | An orchestrator needs them to route traffic only to healthy instances and restart stuck ones (NFR4). |

## Requirements traceability

| Requirement | Status | Implementation | Verified by |
|---|---|---|---|
| FR1 Create wallet | Planned | | |
| FR2 Retrieve balance | Planned | | |
| FR3 Retrieve historical balance | Planned | | |
| FR4 Deposit funds | Planned | | |
| FR5 Withdraw funds | Planned | | |
| FR6 Transfer funds | Planned | | |
| FR7 List transactions | Planned | | |
| NFR1 Full traceability | Planned | | |
| NFR2 Correctness under concurrency | Planned | | |
| NFR3 Idempotency | Planned | | |
| NFR4 Availability | Partial | Actuator health, liveness and readiness endpoints | `HealthEndpointTest` |
| NFR5 Observability | Planned | | |
| NFR6 Monetary precision | Planned | | |
| NFR7 API contract | Planned | | |
| NFR8 Persistence | Planned | | |
| NFR9 Security | Planned | | |
| NFR10 Ease of running | Partial | Maven Wrapper; container setup comes with persistence | |
| NFR11 Testability | Done | JaCoCo gate in `pom.xml`; layering rules | `ArchitectureTest`, `./mvnw verify` |
