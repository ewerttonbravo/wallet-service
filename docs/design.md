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
- JUnit, AssertJ, ArchUnit, JaCoCo, PIT (mutation testing), Testcontainers.
- Docker Compose for local runs: PostgreSQL and [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) as the local token issuer.
- GitHub Actions runs `./mvnw verify` on every push and pull request.

## API

Every endpoint except health requires a bearer JWT from the configured issuer, issued to the `wallet-service` audience. The wallet is always the caller's own, identified by the token subject.

| Method and path | Success | Errors |
|---|---|---|
| `POST /wallets` | `201`, `Location: /wallets/me`, `{id, currency, createdAt}` | `401`; `409` when the user already has a wallet |
| `GET /wallets/me` | `200 {id, currency, createdAt}` | `401`; `404` when the user has no wallet |
| `GET /wallets/me/balance` | `200 {"balance": "0.00", "currency": "BRL"}` | `401`; `404` when the user has no wallet |
| `POST /wallets/me/deposits` `{"amount": "10.50"}` | `201`, `Location: /wallets/me/transactions/{id}`, the transaction | `400` malformed request; `401`; `404` no wallet; `422` amount not positive, more than 2 decimals or beyond the storable bound, or a balance that would exceed it; `503` wallet busy |
| `POST /wallets/me/withdrawals` `{"amount": "10.50"}` | same as deposits | same as deposits; `422` when the balance is not enough |
| `GET /wallets/me/transactions/{id}` | `200`, the transaction | `400` id not a UUID; `401`; `404` no wallet, or no such transaction on the caller's wallet |
| `GET /actuator/health`, `/liveness`, `/readiness` | `200`, public | |

A transaction is shown as the caller's wallet sees it: `{"id", "type": "DEPOSIT" | "WITHDRAWAL", "amount": "-10.50", "balance": "0.00", "currency": "BRL", "correlationId", "createdAt"}`. The amount is signed (negative when money left the wallet) and `balance` is the wallet's balance right after it.

Amounts are decimal strings; a JSON number is refused. A request is `400` when it is not well-formed (invalid JSON, `amount` missing or not a plain decimal string), and `422` when it is well-formed but breaks a rule.

Every response carries `X-Correlation-Id`: the caller's, if it sent a valid one, or a generated UUID. A malformed one is refused with `400`.

`503` comes with `Retry-After: 1`. Nothing was posted, so a retry is safe.

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
| D12 | Framework-free application layer; dependencies whitelisted by ArchUnit | Use cases are plain Java, testable without a container. Adapters register them as beans. A whitelist (JDK, domain, application) also catches libraries nobody thought to forbid. A use case marks its transaction boundary through the `Transactions` port, and the persistence adapter implements it (D27). |
| D13 | Spring Data JPA, with a native insert-on-conflict to open a wallet | `insert … on conflict (owner_id) do nothing` lets the unique constraint settle concurrent openings in one statement, with no check-then-insert race. `save()` is avoided because it merges entities with assigned ids instead of inserting them. The repository exposes no delete; its only update is the balance, which must run inside a transaction and fails unless exactly one wallet changes. |
| D14 | Wallet and balance are separate resources | The historical balance (FR3) extends `GET /wallets/me/balance` with an instant, so there is a single way to read a balance. |
| D15 | Amounts as decimal strings with a currency code (`"1234.50"`, `"BRL"`) | JSON numbers are parsed as binary floating point by many clients. Same convention as Brazil's Pix and Open Finance APIs. |
| D16 | Flyway owns the schema; Hibernate only validates it | Versioned SQL migrations are reviewable and repeatable (NFR8). `ddl-auto=validate` makes a mapping that drifts from the schema fail at startup. |
| D17 | Business errors as a sealed hierarchy mapped to Problem Details | An exhaustive `switch` maps each error to a status, so adding an error without mapping it does not compile (NFR7). |
| D18 | Configuration through Spring's standard environment variables, no defaults | `SPRING_DATASOURCE_*` and the JWT issuer must be provided; the service fails fast without them. No credential of a deployed environment is committed; `compose.yaml` holds only a throwaway local one, published on the loopback interface. |
| D19 | Integration tests against a real PostgreSQL in Testcontainers, one shared context | Behavior that matters (constraints, concurrency, `timestamptz` precision) is exercised on the real database. Each test uses a fresh owner, which isolates tests without cleanup. |
| D20 | Adapters must not depend on each other, the wiring package included | `adapter.config` wires only through application ports; adapter classes are package-private, so it could not reach them anyway. A bean that needs a concrete adapter is declared in that adapter's own package, as `SecurityConfiguration` is. Exempting the wiring package would weaken the rule for a case that does not exist. |
| D21 | Token audience fixed to `wallet-service`, overridable per environment | Without an audience check, a token issued to any other service of the same provider would be accepted. Spring skips the check when the setting is absent, so a required variable would fail open if forgotten; a fixed value fails closed. It names the service rather than an environment, so D18 does not apply. |
| D22 | One local issuer, the same image in Docker Compose and in tests | Tests validate tokens the issuer signs through the production decoder, so signature, issuer and audience checks are exercised as they run. API tests keep Spring Security's test tokens, which are faster and keep each test focused on the API. The issuer is started by a context initializer: the JWT decoder is auto-configured only if the issuer URI is set before the context refreshes. |

| D23 | Ledger as transactions and entries; the external account has no row | `ledger_transaction` holds type, correlation id and timestamp; `ledger_entry` holds the account (a wallet, or null for the external account), the signed amount and the wallet's resulting balance. Entries share their transaction's timestamp and correlation id instead of copying them. A running balance on the external account would be one row locked by every posting of every user, so the external account keeps none. An identity key gives a wallet's entries a strict order for the historical balance (FR3). |
| D24 | Signed amounts | Positive raises the account's balance, negative lowers it. A deposit is wallet `+a`, external `-a`; a withdrawal is wallet `-a`, external `+a`. A transaction balances when its amounts sum to zero, with no debit and credit columns to keep in step. |
| D25 | The database refuses what the code never writes | Triggers reject any update, delete or truncate of ledger rows. A deferred trigger rejects at commit a transaction with fewer than two entries or entries that do not sum to zero. Checks keep wallet balances and resulting balances non-negative, amounts non-zero and balances only on wallet entries, and a unique index allows one entry per wallet per transaction. The domain enforces the same rules first; the database is the last line of defense. |
| D26 | One posting = one transaction under a row lock | The use case locks the wallet (`select … for update`), applies the rule, stores the new balance and appends the ledger transaction, in one database transaction. The clock is read under the lock, so a wallet's entries are timestamped in posting order. The lock, the balance update and the append fail outside a transaction (`MANDATORY`), so none can run alone. |
| D27 | `Transactions` port in the application layer | The use case declares where its transaction starts and ends; the persistence adapter implements it with `TransactionTemplate`. Every caller gets exactly one transaction per use case by construction, and unit tests run the use cases with a fake that just calls the work. |
| D28 | Ledger written with plain SQL (`JdbcClient`) | Ledger rows are only inserted and read, so they need no entity lifecycle, and the SQL shows exactly what is written. The sealed entry type maps to rows with an exhaustive `switch`. The wallet stays on Spring Data JPA (D13). |
| D29 | The wallet lock waits at most two seconds | The lock query carries a lock-timeout hint, which Hibernate applies as `set local lock_timeout`: it ends with the transaction and does not reach other statements or migrations. A wallet held longer by other operations answers `503` with `Retry-After`, instead of holding a thread and a connection until the client gives up. The transaction is rolled back, so the retry cannot post twice. |
| D30 | Format checked at the boundary, rules in the domain | The request type exposes only `amount`. The adapter refuses what is not well-formed (`400`): JSON numbers (strict coercion for string fields), anything but a plain decimal string, more than 32 characters. The domain refuses what breaks a rule (`422`): not positive, more than 2 decimals, beyond the storable bound (A12), insufficient funds. The positive-amount rule is also checked before the lock, so a request that can never succeed does not wait for it. |
| D31 | Correlation id taken or generated by a servlet filter before security | The id comes from `X-Correlation-Id` (A13) or is generated, is stored on the ledger transaction and is returned on every response, rejected requests included. A malformed value is refused with a fixed message and never echoed. |
| D32 | Mutation testing on the domain and application layers | Coverage shows a line ran, not that a test would fail if it were wrong. PIT mutates the business rules on every `verify` and fails the build unless every mutant is killed. Adapters are covered by integration tests on the real stack instead. |

## Requirements traceability

| Requirement | Status | Implementation | Verified by |
|---|---|---|---|
| FR1 Create wallet | Done | `POST /wallets`, `WalletService.open`, unique owner constraint | `WalletApiTest`, `WalletServiceTest`, `WalletRepositoryContract`, `WalletCreationConcurrencyTest` |
| FR2 Retrieve balance | Done | `GET /wallets/me/balance` | `WalletApiTest` |
| FR3 Retrieve historical balance | Planned | | |
| FR4 Deposit funds | Done | `POST /wallets/me/deposits`, `Wallet.deposit`, `WalletService.deposit` | `WalletTest`, `WalletServiceTest`, `TransactionApiTest` |
| FR5 Withdraw funds | Done | `POST /wallets/me/withdrawals`, `Wallet.withdraw`, `WalletService.withdraw`; overdraft refused in the domain and by a check constraint | `WalletTest`, `WalletServiceTest`, `TransactionApiTest`, `LedgerConcurrencyTest` |
| FR6 Transfer funds | Planned | | |
| FR7 List transactions | Partial | `GET /wallets/me/transactions/{id}` reads one transaction of the caller's wallet; the paginated listing is pending | `TransactionApiTest`, `LedgerContract` |
| NFR1 Full traceability | Done for deposits and withdrawals | Double-entry `LedgerTransaction`; `ledger_transaction` and `ledger_entry` with append-only and balance triggers; correlation id on every transaction | `LedgerTransactionTest`, `LedgerSchemaTest`, `LedgerContract`, `CorrelationIdApiTest`, reconciliation in `LedgerConcurrencyTest` |
| NFR2 Correctness under concurrency | Partial | One transaction per posting under `select … for update`, bounded lock wait, non-negative balance check; deterministic lock order for transfers pending | `LedgerConcurrencyTest`, `LockTimeoutApiTest`, `JpaWalletRepositoryTest` |
| NFR3 Idempotency | Planned | | |
| NFR4 Availability | Partial | Actuator health, liveness and readiness endpoints | `HealthEndpointTest` |
| NFR5 Observability | Planned | | |
| NFR6 Monetary precision | Done | `Money`: `BigDecimal` scale 2, BRL, finer amounts rejected, bounded by `numeric(19, 2)`; decimal strings in JSON, numbers refused | `MoneyTest`, `WalletApiTest`, `TransactionApiTest` |
| NFR7 API contract | Partial | REST + JSON, Problem Details for business errors, ISO-8601 UTC timestamps; OpenAPI pending | `WalletApiTest`, `TransactionApiTest`, `WalletProblemHandlerTest` |
| NFR8 Persistence | Done | PostgreSQL, Flyway migrations, schema validated at startup | `JpaWalletRepositoryTest`, `JdbcLedgerTest`, `LedgerSchemaTest` |
| NFR9 Security | Done | JWT resource server: signature, issuer, expiry and audience validated; owner from the token subject; a transaction is found only through the caller's wallet | `WalletApiTest`, `JwtAuthenticationTest`, `TransactionApiTest` |
| NFR10 Ease of running | Partial | Maven Wrapper; PostgreSQL and the local issuer in Docker Compose; tests provision both with Testcontainers; service container pending | `./mvnw verify`, README run steps |
| NFR11 Testability | Done | JaCoCo and PIT gates in `pom.xml`; framework-free domain and application; layering rules | `ArchitectureTest`, `./mvnw verify` |
