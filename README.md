# Wallet Service

[![CI](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml)

A microservice that keeps users' wallets: it opens wallets, takes deposits and withdrawals, moves funds from one user to another, and reports current and past balances. Each operation is recorded in an append-only ledger, so any balance can be audited and rebuilt.

Built with Java 25 and Spring Boot, following test-driven development and a clean separation between the domain and the technology around it.

## Status

Work in progress. Wallets, balances, deposits, withdrawals and transfers work today. Every deposit, withdrawal and transfer carries an idempotency key: sent again with the same key it is posted only once, and the key cannot be reused for another request. The transaction history and the balance at a past instant come next, in the order of the [roadmap](docs/roadmap.md).

## Build and test

Requires JDK 25 and a running Docker daemon: integration tests start their own PostgreSQL and token issuer with Testcontainers. The Maven Wrapper downloads Maven on first use.

```sh
./mvnw verify
```

The build fails if line or branch coverage of the domain or application code drops below 100%, if a mutant of the domain or application code survives the tests (PIT, report in `target/pit-reports`), or if the domain or application layers depend on any framework.

With Colima instead of Docker Desktop, point Testcontainers to its socket first:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Run

Requires Docker with Compose, plus `curl` and `jq` for the examples. Start PostgreSQL and the local token issuer:

```sh
docker compose up -d --wait
```

The service reads its database and issuer from environment variables and refuses to start without them. Flyway creates the schema on startup. Check that the service is up with `curl localhost:8080/actuator/health`.

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet
export SPRING_DATASOURCE_USERNAME=wallet
export SPRING_DATASOURCE_PASSWORD=wallet
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=http://localhost:9000/local
./mvnw spring-boot:run
```

In another terminal, get a token for a user (`client_id` becomes the token subject, `scope` its audience) and call the API:

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=alice -d client_secret=any \
  -d scope=wallet-service http://localhost:9000/local/token | jq -r .access_token)

curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/wallets
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/wallets/me/balance
```

Deposit and withdraw. Amounts are decimal strings with at most two decimal places. Each call answers with the transaction it posted and a `Location` to read it back. Deposits, withdrawals and transfers require an `Idempotency-Key` (1 to 64 letters, digits, `.`, `_`, `:` or `-`), new for each operation: sent again with the same key, the operation answers with the transaction it already posted and moves no money; with the same key and another request (another amount, recipient or operation), it is refused with `422`.

```sh
curl -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-1" -H "X-Correlation-Id: my-request-1" \
  -d '{"amount": "100.00"}' http://localhost:8080/wallets/me/deposits
curl -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: withdrawal-1" \
  -d '{"amount": "30.50"}' http://localhost:8080/wallets/me/withdrawals
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/wallets/me/balance
```

Read a transaction back with the `Location` path of its response (`curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/wallets/me/transactions/<id>`). `X-Correlation-Id` is optional: the service stores it with the transaction and returns it, or generates one when it is missing.

Transfer to another user. The recipient is named by its wallet id, the `id` that `GET /wallets/me` returns to its owner:

```sh
BOB_TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=bob -d client_secret=any \
  -d scope=wallet-service http://localhost:9000/local/token | jq -r .access_token)
BOB_WALLET=$(curl -s -X POST -H "Authorization: Bearer $BOB_TOKEN" http://localhost:8080/wallets | jq -r .id)

curl -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer-1" \
  -d "{\"recipientWalletId\": \"$BOB_WALLET\", \"amount\": \"20.00\"}" http://localhost:8080/wallets/me/transfers
curl -H "Authorization: Bearer $BOB_TOKEN" http://localhost:8080/wallets/me/balance
```

Both wallets see the transfer at the same `Location`, each with its own signed amount and balance, and the other wallet as `counterpartyWalletId`. A transfer sent again with the same key replays only when it names the same recipient and amount; for another recipient, it is refused with `422`.

Tokens for any audience other than `wallet-service` are rejected with `401`. If ports 5432 or 9000 are taken, change the host side of `ports` in `compose.yaml` and the matching variable. Stop everything with `docker compose down -v`.

## Documentation

- [Requirements](docs/requirements.md): functional and non-functional requirements, assumptions, scope.
- [Design](docs/design.md): architecture, decisions, and how each requirement is met.
- [Roadmap](docs/roadmap.md): increments, their order and the definition of done.
- [Trade-offs](docs/trade-offs.md): compromises, their production path, and time spent.
