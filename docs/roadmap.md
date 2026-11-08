# Roadmap

The service is built in increments, ordered from the most to the least value delivered to the user. Each increment lives in its own branch and is merged into `main` only when it is tested, documented and passes `./mvnw verify`.

## Increments

| # | Branch | Delivers | Requirements |
|---|---|---|---|
| 1 | `sprint/01-wallet` | Create a wallet and retrieve its balance, with the owner taken from the JWT and data stored in PostgreSQL | FR1, FR2, NFR6, NFR8, NFR7 and NFR9 partially |
| 2 | `sprint/02-local-issuer` | A local token issuer and PostgreSQL in Docker Compose, so the service can be run and called with real tokens; audience validation | NFR9, NFR10 partially |
| 3 | `sprint/03-deposit-withdraw` | Deposits and withdrawals on a double-entry ledger, pessimistic locking, non-negative balance constraint, correlation id recorded on every entry; a single transaction can be read back | FR4, FR5, NFR1, NFR2 partially, FR7 partially |
| 4 | `sprint/04-transfer` | Atomic transfers, locks acquired in a deterministic order, deadlock-free concurrency test | FR6, NFR2 |
| 5 | `sprint/05-idempotency` | `Idempotency-Key` on deposits, withdrawals and transfers: a replay returns the original result, a different payload is rejected | NFR3 |
| 6 | `sprint/06-history` | Historical balance at an instant and paginated transaction listing | FR3, FR7 |
| 7 | `sprint/07-one-command-run` | Container image of the service, added to Docker Compose next to PostgreSQL and the issuer; usage examples | NFR10 |
| 8 | `sprint/08-operability` | OpenAPI, structured logs, metrics, graceful shutdown | NFR4, NFR5, NFR7 |
| 9 | `sprint/09-delivery` | Final review of code and documentation: assumptions, trade-offs, time spent | Delivery |

## Ordering rationale

- JWT authentication comes in the first increment because the API shape depends on it: the wallet is addressed as the caller's own (`/wallets/me`), not by a user id in the path. Adding it later would break the contract.
- The local token issuer comes right after, ahead of new operations: without it the first increment could be exercised only by tests, and every later increment can be tried by hand as soon as it is merged.
- Idempotency comes before history: moving money twice on a client retry is worse than not being able to look back in time.
- Operability and API documentation come last because they make a working service easier to run and observe, but deliver no new capability to the wallet owner.

## Definition of done

- `./mvnw verify` passes: tests, 100% line and branch coverage of the domain and application layers, every mutant of the domain and application layers killed, architecture rules.
- [design.md](design.md) updated: new decisions and the requirements traceability matrix.
- [trade-offs.md](trade-offs.md) updated: new compromises and time spent.
- README updated when installing, testing or running changes.
- Reviewed in a pull request and merged into `main` with a merge commit, never squashed, keeping the test-first commit trail grouped by increment.
