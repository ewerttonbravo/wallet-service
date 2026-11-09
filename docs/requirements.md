# Requirements

This document defines what the Wallet Service must do (functional requirements), the qualities it must have (non-functional requirements), and the assumptions that close the gaps in the original brief.

## Functional requirements

| ID | Requirement | Rules |
|---|---|---|
| FR1 | Create wallet | A user owns at most one wallet. The owner is the authenticated user; users are managed outside this service. A new wallet starts with a zero balance. Creating a second wallet for the same user is rejected as a conflict. |
| FR2 | Retrieve balance | Returns the current balance of the wallet. Never exposes an intermediate state of an in-flight operation. |
| FR3 | Retrieve historical balance | The balance at instant `T` is the balance after the last ledger entry recorded at or before `T`. `T` before the wallet's creation or in the future is rejected. |
| FR4 | Deposit funds | Amount must be positive with at most 2 decimal places. Amounts with more decimal places are rejected, never rounded. |
| FR5 | Withdraw funds | Same amount rules as FR4. Rejected when the balance is insufficient; overdraft is not allowed. |
| FR6 | Transfer funds | Moves money from the authenticated user's wallet to another wallet, atomically: both sides are applied or neither is. Same amount rules as FR4. Transfers to the same wallet are rejected. Rejected when the source balance is insufficient. |
| FR7 | List transactions | Returns the wallet's ledger entries, newest first, paginated. Exposes the audit trail to the owner. |

## Non-functional requirements

| ID | Requirement | Approach |
|---|---|---|
| NFR1 | Full traceability | Double-entry, append-only ledger. Every operation is a transaction whose entries sum to zero. Entries are never updated or deleted. Each entry records its transaction, account, signed amount, timestamp and correlation id; each wallet entry also records the wallet's resulting balance. The external account keeps no running balance: its balance is the sum of its entries. Any balance can be rebuilt and reconciled from the ledger. |
| NFR2 | Correctness under concurrency | ACID transactions. Pessimistic row locks (`SELECT … FOR NO KEY UPDATE`) on the affected wallets. Transfers lock wallets in a deterministic order to prevent deadlocks. A database `CHECK` constraint keeps user balances non-negative as a last line of defense. |
| NFR3 | Idempotency | Deposit, withdrawal and transfer require an `Idempotency-Key` header. Replaying a key returns the original result without posting again. Reusing a key with a different payload is rejected. |
| NFR4 | Availability | Stateless service, horizontally scalable. Liveness and readiness probes. Graceful shutdown that drains in-flight requests. |
| NFR5 | Observability | Structured logs carrying a correlation id, application metrics, health endpoints. |
| NFR6 | Monetary precision | `BigDecimal` with scale 2. Single currency: BRL. |
| NFR7 | API contract | REST over HTTP with JSON, documented with OpenAPI. Errors follow Problem Details (RFC 9457). Timestamps are ISO-8601 with offset; storage is UTC. |
| NFR8 | Persistence | PostgreSQL with versioned schema migrations. |
| NFR9 | Security | Every endpoint requires a JWT, validated by the service as an OAuth2 resource server. The token subject identifies the user; users can only read and move money from their own wallet. TLS is terminated by the infrastructure (gateway or ingress), not by the service. |
| NFR10 | Ease of running | Docker is the only prerequisite. One command starts the service, its database and a local token issuer. Tests provision their own database and token issuer. |
| NFR11 | Testability | 100% line and branch coverage of the domain and application layers, and every mutant there killed, enforced by the build. Adapters are tested where they hold behavior of this service, not to cover framework glue. The domain is independent of frameworks and tested with plain unit tests. |

The design decisions that shape these requirements are recorded in [design.md](design.md#decisions).

## Assumptions

Where the brief leaves a behavior undefined, these assumptions close the gap. The requirements above are built on them.

| ID | Assumption | Rationale |
|---|---|---|
| A1 | Users are created and authenticated elsewhere; the JWT subject is the user id. | Identity is a separate concern with its own lifecycle and security needs. The wallet only needs to know who is calling. |
| A2 | A user owns at most one wallet. | It serves every required operation. Multiple wallets would force clients to pick one on every call, with no requirement asking for it. |
| A3 | Single currency: BRL. | No requirement mentions currencies. Multi-currency brings conversion rates and per-currency balances for no stated benefit. |
| A4 | Amounts are positive with at most 2 decimal places; anything finer is rejected, never rounded. | Rounding silently changes the amount the client asked to move. Rejecting makes the error visible to the caller. |
| A5 | Withdrawals and transfers cannot overdraw a wallet. | Credit is a product of its own, with limits and interest. Without such a requirement, a wallet holds only money the user has. |
| A6 | The balance at instant `T` is the balance after the last ledger entry recorded at or before `T`. `T` in the future or before the wallet's creation is rejected. | The ledger gives an exact answer for any past instant. The future has no balance yet, and answering zero before the wallet existed would hide a client error. |
| A7 | Transfers to the same wallet are rejected. | They move no money and only add noise to the audit trail; they almost always indicate a client bug. |
| A8 | Deposits and withdrawals represent money already settled with an external system (payment provider, bank). | Integrating with payment rails is a separate concern. The wallet records the outcome. |
| A9 | No minimum or maximum amount per operation and no daily limits. | Limits are business policy. Inventing values would add rules nobody asked for. |
| A10 | Wallets are never closed or deleted. | Closure raises questions (remaining balance, retention) the brief does not answer, and the ledger must remain available for auditing anyway. |
| A11 | Idempotency keys are kept for as long as the transaction they produced. | A retry may arrive at any time; expiring keys would reopen the door to moving money twice. |
| A12 | Amounts and balances are bounded by what the ledger stores: at most 99,999,999,999,999,999.99 in magnitude. A larger amount, or a deposit that would take the balance past it, is rejected. | The bound is technical, not a business limit (A9 still holds). Rejecting it is a clear client error instead of a database failure. |
| A13 | A caller may send its own correlation id in `X-Correlation-Id` (1 to 64 letters, digits, `.`, `_`, `:` or `-`); without one, the service generates a UUID. | Gateways and upstream services already trace requests with their own ids; reusing them ties a ledger entry to the whole request path. The format keeps untrusted input out of headers and the audit trail. |
| A14 | The recipient of a transfer is named by its wallet id, which its owner shares outside the service, like an account number. | The wallet id is opaque and local to this service. The identity provider's subject may be guessable or personal data, and would tie the API to that provider. Directories of aliases (phone, e-mail) belong to user management, which is out of scope. |
| A15 | A transfer to a wallet that does not exist is a rule violation (`422`), not a missing resource. | The resource called, the caller's transfers, exists; the request names something invalid. `404` keeps one meaning: the caller has no wallet. |
| A16 | An idempotency key belongs to the wallet that sent it, whatever the operation. The same key sent by another user names nothing of theirs. | Keys are chosen by clients, so two users can pick the same one; scoping by wallet keeps one user's retry from ever reaching another user's transaction. One key per wallet, across operations, lets a key reused on another operation be refused (A19) instead of posting twice. |
| A17 | An idempotency key is 1 to 64 letters, digits, `.`, `_`, `:` or `-`, sent as is (no quotes). A missing or malformed key is refused with `400`. | The same rule as the correlation id (A13): it fits UUIDs and keys of the client's own making, and keeps untrusted input out of the audit trail. |
| A18 | Only an accepted posting keeps its idempotency key. A refused request (rule broken, no wallet, wallet busy) records nothing, so a retry with the same key runs again. A replay returns the original response: the same `201`, `Location` and body. | A refusal moves no money, so running it again cannot post twice, and a transient refusal such as a busy wallet must not stick to the key. Answering a replay exactly as the first time lets a client that lost the response carry on as if it had received it. |
| A19 | A key already used replays only the same request: the same operation, the same amount, compared as money (`"10.5"` is `"10.50"`), and for a transfer the same recipient. Anything else is refused with `422` and posts nothing. The correlation id is not part of the request. | A key reused for something else is a client bug; replaying the original would tell the client its new request was posted when it was not, or, for a transfer to someone else, that it paid someone it did not. Comparing money, not text, keeps a retry that formats the amount differently a retry. Each attempt may carry its own correlation id (A13), so it cannot tell a retry from a new request. |
| A20 | The history is paged by number and size: `page` from 0 (default 0), `size` from 1 to 100 (default 20); anything else is refused with `400`. Each page carries the totals and a link to the next one; a page past the end is empty. Newest first means in the reverse of the order the wallet's transactions were posted. | Page numbers and totals are what most clients and people expect to browse a statement. A bound on the size keeps one request from reading a whole history; 20 fits a screen. Posting order is the ledger's own, exact for every wallet, where timestamps can tie. |
| A21 | The instant of a historical balance is ISO-8601 with an offset (`2026-11-09T10:00:00Z`, `2026-11-09T07:00:00-03:00`); without one, or malformed, it is refused with `400`. An instant finer than the microsecond is read at the microsecond it falls in. | Without an offset the same text names another instant in every time zone; reading it as UTC would answer the wrong balance without telling the client. The ledger stores microseconds, so an entry is at or before an instant exactly when it is at or before the microsecond the instant falls in. |

## Out of scope

- User management and token issuance.
- Multi-currency and currency conversion.
- Fees, limits, fraud checks.
- Wallet closure.
- High availability of the database itself (replication, failover, multi-region). Documented as a trade-off.
