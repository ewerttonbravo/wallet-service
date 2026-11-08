# Design

How the Wallet Service is built and how it meets the [requirements](requirements.md). This document grows with each increment.

## Architecture

The service follows a hexagonal (ports and adapters) layout inside a single Maven module, under the base package `com.ebravo.wallet`:

| Package | Responsibility | May depend on |
|---|---|---|
| `domain` | Wallets, ledger, money and the business rules around them | The JDK only |
| `application` | Use cases that orchestrate the domain through ports | `domain` and the JDK only |
| `adapter` | HTTP, persistence, security and other technology | `application`, `domain` |

Dependencies point inward: technology depends on the domain, never the other way around. Adapters do not depend on each other. The rules are enforced by `ArchitectureTest`, so a violation fails the build instead of waiting for code review.

## Technology

- Java 25, Spring Boot 4.1.
- PostgreSQL 18, Spring Data JPA (Hibernate), Flyway.
- Spring Security as an OAuth2 resource server (JWT).
- Maven through the Maven Wrapper.
- JUnit 5, AssertJ, ArchUnit, JaCoCo, Testcontainers.
- Docker Compose for local runs: PostgreSQL and [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) as the local token issuer.
- GitHub Actions runs `./mvnw verify` on every push and pull request.

## API

Every endpoint except health requires a bearer JWT from the configured issuer, issued to the `wallet-service` audience. The wallet is always the caller's own, identified by the token subject.

| Method and path | Success | Errors |
|---|---|---|
| `POST /wallets` | `201`, `Location: /wallets/me`, `{id, currency, createdAt}` | `401`; `409` when the user already has a wallet |
| `GET /wallets/me` | `200 {id, currency, createdAt}` | `401`; `404` when the user has no wallet |
| `GET /wallets/me/balance` | `200 {"balance": "0.00", "currency": "BRL"}` | `401`; `404` when the user has no wallet |
| `GET /actuator/health`, `/liveness`, `/readiness` | `200`, public | |

Errors other than `401` are Problem Details (`application/problem+json`). Timestamps are ISO-8601 in UTC.

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
| D12 | Framework-free application layer; dependencies whitelisted by ArchUnit | Use cases are plain Java, testable without a container. Adapters register them as beans and own transaction demarcation. A whitelist (JDK, domain, application) also catches libraries nobody thought to forbid. |
| D13 | Spring Data JPA, with a native insert-on-conflict to open a wallet | `insert … on conflict (owner_id) do nothing` lets the unique constraint settle concurrent openings in one statement, with no check-then-insert race. `save()` is avoided because it merges entities with assigned ids instead of inserting them. The repository exposes no update or delete. |
| D14 | Wallet and balance are separate resources | The historical balance (FR3) extends `GET /wallets/me/balance` with an instant, so there is a single way to read a balance. |
| D15 | Amounts as decimal strings with a currency code (`"1234.50"`, `"BRL"`) | JSON numbers are parsed as binary floating point by many clients. Same convention as Brazil's Pix and Open Finance APIs. |
| D16 | Flyway owns the schema; Hibernate only validates it | Versioned SQL migrations are reviewable and repeatable (NFR8). `ddl-auto=validate` makes a mapping that drifts from the schema fail at startup. |
| D17 | Business errors as a sealed hierarchy mapped to Problem Details | An exhaustive `switch` maps each error to a status, so adding an error without mapping it does not compile (NFR7). |
| D18 | Configuration through Spring's standard environment variables, no defaults | `SPRING_DATASOURCE_*` and the JWT issuer must be provided; the service fails fast without them and no credential is committed. |
| D19 | Integration tests against a real PostgreSQL in Testcontainers, one shared context | Behavior that matters (constraints, concurrency, `timestamptz` precision) is exercised on the real database. Each test uses a fresh owner, which isolates tests without cleanup. |
| D20 | Adapters must not depend on each other, the wiring package included | `adapter.config` wires only through application ports; adapter classes are package-private, so it could not reach them anyway. A bean that needs a concrete adapter is declared in that adapter's own package, as `SecurityConfiguration` is. Exempting the wiring package would weaken the rule for a case that does not exist. |
| D21 | Token audience fixed to `wallet-service`, overridable per environment | Without an audience check, a token issued to any other service of the same provider would be accepted. Spring skips the check when the setting is absent, so a required variable would fail open if forgotten; a fixed value fails closed. It names the service rather than an environment, so D18 does not apply. |
| D22 | One local issuer, the same image in Docker Compose and in tests | Tests validate tokens the issuer signs through the production decoder, so signature, issuer and audience checks are exercised as they run. API tests keep Spring Security's test tokens, which are faster and keep each test focused on the API. The issuer is started by a context initializer: the JWT decoder is auto-configured only if the issuer URI is set before the context refreshes. |

## Requirements traceability

| Requirement | Status | Implementation | Verified by |
|---|---|---|---|
| FR1 Create wallet | Done | `POST /wallets`, `WalletService.open`, unique owner constraint | `WalletApiTest`, `WalletServiceTest`, `WalletRepositoryContract`, `WalletCreationConcurrencyTest` |
| FR2 Retrieve balance | Done | `GET /wallets/me/balance` | `WalletApiTest` |
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
| NFR6 Monetary precision | Done | `Money`: `BigDecimal` scale 2, BRL, finer amounts rejected; `numeric(19, 2)`; decimal strings in JSON | `MoneyTest`, `WalletApiTest` |
| NFR7 API contract | Partial | REST + JSON, Problem Details for business errors, ISO-8601 UTC timestamps; OpenAPI pending | `WalletApiTest` |
| NFR8 Persistence | Done | PostgreSQL, Flyway migrations, schema validated at startup | `JpaWalletRepositoryTest` |
| NFR9 Security | Done | JWT resource server: signature, issuer, expiry and audience validated; owner from the token subject | `WalletApiTest`, `JwtAuthenticationTest` |
| NFR10 Ease of running | Partial | Maven Wrapper; PostgreSQL and the local issuer in Docker Compose; tests provision both with Testcontainers; service container pending | `./mvnw verify`, README run steps |
| NFR11 Testability | Done | JaCoCo gate in `pom.xml`; framework-free domain and application; layering rules | `ArchitectureTest`, `./mvnw verify` |
