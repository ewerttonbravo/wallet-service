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
| NFR1 | Full traceability | Double-entry, append-only ledger. Every operation is a transaction whose entries sum to zero. Entries are never updated or deleted. Each entry records its transaction, account, signed amount, resulting balance, timestamp and correlation id. Any balance can be rebuilt and reconciled from the ledger. |
| NFR2 | Correctness under concurrency | ACID transactions. Pessimistic row locks (`SELECT … FOR UPDATE`) on the affected wallets. Transfers lock wallets in a deterministic order to prevent deadlocks. A database `CHECK` constraint keeps user balances non-negative as a last line of defense. |
| NFR3 | Idempotency | Deposit, withdrawal and transfer require an `Idempotency-Key` header. Replaying a key returns the original result without posting again. Reusing a key with a different payload is rejected. |
| NFR4 | Availability | Stateless service, horizontally scalable. Liveness and readiness probes. Graceful shutdown that drains in-flight requests. |
| NFR5 | Observability | Structured logs carrying a correlation id, application metrics, health endpoints. |
| NFR6 | Monetary precision | `BigDecimal` with scale 2. Single currency: BRL. |
| NFR7 | API contract | REST over HTTP with JSON, documented with OpenAPI. Errors follow Problem Details (RFC 9457). Timestamps are ISO-8601 with offset; storage is UTC. |
| NFR8 | Persistence | PostgreSQL with versioned schema migrations. |
| NFR9 | Security | Every endpoint requires a JWT, validated by the service as an OAuth2 resource server. The token subject identifies the user; users can only read and move money from their own wallet. TLS is terminated by the infrastructure (gateway or ingress), not by the service. |
| NFR10 | Ease of running | Docker is the only prerequisite. One command starts the service, its database and a local token issuer. Tests provision their own database. |
| NFR11 | Testability | 100% line and branch coverage enforced by the build. The domain is independent of frameworks and tested with plain unit tests. |

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

## Out of scope

- User management and token issuance.
- Multi-currency and currency conversion.
- Fees, limits, fraud checks.
- Wallet closure.
- High availability of the database itself (replication, failover, multi-region). Documented as a trade-off.
