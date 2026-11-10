# Roadmap

The service is built in increments, ordered from the most to the least value delivered to the user. Each increment lives in its own branch and is merged into `main` only when it is tested, documented and passes `./mvnw verify`.

## Increments

| # | Branch | Delivers | Requirements |
|---|---|---|---|
| 1 | `sprint/01-wallet` | Create a wallet and retrieve its balance, with the owner taken from the JWT and data stored in PostgreSQL | FR1, FR2, NFR6, NFR8, NFR7 and NFR9 partially |
| 2 | `sprint/02-local-issuer` | A local token issuer and PostgreSQL in Docker Compose, so the service can be run and called with real tokens; audience validation | NFR9, NFR10 partially |
| 3 | `sprint/03-deposit-withdraw` | Deposits and withdrawals on a double-entry ledger, pessimistic locking, non-negative balance constraint, correlation id recorded on every entry; a single transaction can be read back | FR4, FR5, NFR1, NFR2 partially, FR7 partially |
| 4 | `sprint/04-transfer` | Atomic transfers, locks acquired in a deterministic order, deadlock-free concurrency test | FR6, NFR2 |
| 5 | `sprint/05-idempotent-deposit` | `Idempotency-Key` required on deposits: a replay, concurrent or not, returns the original result without posting again | NFR3 partially |
| 6 | `sprint/06-idempotency-conflict` | Reusing an idempotency key with a different payload is rejected | NFR3 partially |
| 7 | `sprint/07-idempotent-withdraw-transfer` | The same idempotency contract on withdrawals and transfers | NFR3 |
| 8 | `sprint/08-transaction-list` | Paginated listing of the wallet's transactions, newest first | FR7 |
| 9 | `sprint/09-historical-balance` | Balance at an instant | FR3 |
| 10 | `sprint/10-service-image` | Container image of the service in Docker Compose next to PostgreSQL and the issuer, one issuer identity for clients and the service; usage examples | NFR10 |
| 11 | `sprint/11-openapi` | OpenAPI contract served by the service | NFR7 |
| 12 | `sprint/12-auth-problem-details` | Problem Details body on `401` and `403` | NFR7 |
| 13 | `sprint/13-structured-logs` | Structured logs carrying the correlation id | NFR5 partially |
| 14 | `sprint/14-metrics` | Application metrics | NFR5 |
| 15 | `sprint/15-database-readiness` | Database in the readiness group; `503` on connection failures | NFR4 partially |
| 16 | `sprint/16-issuer-outage` | `503` with `Retry-After` when the issuer's keys cannot be read | NFR4 partially, NFR7 |
| 17 | `sprint/17-statement-timeout` | A time budget bounding a whole transaction | NFR2, NFR4 partially |
| 18 | `sprint/18-graceful-shutdown` | Graceful shutdown that drains in-flight requests | NFR4 |
| 19 | `sprint/19-delivery` | Final review of code and documentation: assumptions, trade-offs, time spent | Delivery |

## Sizing rule

Each increment delivers one behavior that the wallet owner or the operator can observe. When planning reveals more than one, the increment is split before it is built. Increments 3 and 4 each bundled several behaviors and produced pull requests of 1,200 to 2,500 lines; one behavior keeps a pull request reviewable in one sitting.

## Ordering rationale

- JWT authentication comes in the first increment because the API shape depends on it: the wallet is addressed as the caller's own (`/wallets/me`), not by a user id in the path. Adding it later would break the contract.
- The local token issuer comes right after, ahead of new operations: without it the first increment could be exercised only by tests, and every later increment can be tried by hand as soon as it is merged.
- Idempotency comes before history: moving money twice on a client retry is worse than not being able to look back in time.
- Idempotency is built on one endpoint first, then extended: the storage and replay mechanism is proven on deposits, payload conflicts come next, and withdrawals and transfers reuse what is already in place.
- Operability and API documentation come last because they make a working service easier to run and observe, but deliver no new capability to the wallet owner. Within them, the API contract comes first, then observability, then resilience.

## Definition of done

- `./mvnw verify` passes: tests, 100% line and branch coverage of the domain and application layers, every mutant of the domain and application layers killed, architecture rules.
- [design.md](design.md) updated: new decisions and the requirements traceability matrix.
- [trade-offs.md](trade-offs.md) updated: new compromises and time spent.
- README updated when installing, testing or running changes.
- Reviewed in a pull request and merged into `main` with a merge commit, never squashed, keeping the test-first commit trail grouped by increment.
