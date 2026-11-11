# Wallet Service

[![CI](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml)

A microservice that keeps users' wallets: it opens wallets, takes deposits and withdrawals, moves funds from one user to another, and reports current and past balances. Each operation is recorded in an append-only ledger, so any balance can be audited and rebuilt.

Built with Java 25 and Spring Boot, following test-driven development and a clean separation between the domain and the technology around it.

## Status

Complete: every increment of the [roadmap](docs/roadmap.md) is built. Wallets, current and past balances, deposits, withdrawals, transfers and the transaction history work today. Every deposit, withdrawal and transfer carries an idempotency key: sent again with the same key it is posted only once, and the key cannot be reused for another request. The service runs in Docker Compose next to its database and a local token issuer, and serves its OpenAPI contract. It writes structured logs and metrics, bounds every request it serves, answers `503` when its database or token issuer is unavailable, and drains the requests in flight when it stops.

## Build and test

Requires JDK 25 and a running Docker daemon: integration tests start their own PostgreSQL and token issuer with Testcontainers. The Maven Wrapper downloads Maven on first use.

With Colima instead of Docker Desktop, start it and point Testcontainers to its socket first, in the shell that runs the build:

```sh
colima start
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Then build and test:

```sh
./mvnw verify
```

The build fails if line or branch coverage of the domain or application code drops below 100%, if a mutant of the domain or application code survives the tests (PIT, report in `target/pit-reports`), if the domain or application layers depend on any framework, or if an API test sends or receives something the [OpenAPI contract](src/main/resources/openapi.yaml) does not describe.

## Run

Requires Docker with Compose, plus `curl` and `jq` for the examples. If port 8080, 5432 or 9000 is taken, change the host side of `ports` in `compose.yaml`, and the URLs that name it: for 8080, `api` in `bruno/environments/local.bru` and the examples below; for 9000, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` in `compose.yaml`, `issuer` and `untrustedIssuer` in `bruno/environments/local.bru`, and the examples below. Build the service image and start it with PostgreSQL and the local token issuer:

```sh
docker compose up -d --wait --build
```

The first build downloads the dependencies and takes a few minutes; later builds reuse them. Flyway creates the schema on startup. Check that the service is ready with `curl localhost:8080/readyz` (liveness is `/livez`).

Get a token for a user (`client_id` becomes the token subject, `scope` its audience) and call the API:

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

List the wallet's transactions, newest first, a page at a time (`page` from 0, `size` from 1 to 100, 20 by default). The response carries the totals and, until the last page, the path of the next one in `next`:

```sh
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/wallets/me/transactions?size=2"
```

Read the balance at a past instant with `at`, an ISO-8601 instant with offset, here the moment of the oldest transaction on the first page. `--data-urlencode` keeps a `+` offset intact, which a raw query string would read as a space. An instant before the wallet was opened or in the future is refused with `422`:

```sh
AT=$(curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/wallets/me/transactions" | jq -r '.transactions[-1].createdAt')
curl -G -H "Authorization: Bearer $TOKEN" --data-urlencode "at=$AT" http://localhost:8080/wallets/me/balance
```

The service logs one JSON object per line, in Elastic Common Schema, with a line for every request: method, path, status, duration in nanoseconds and correlation id. Follow one request with the id it was sent or answered with:

```sh
docker compose logs --no-log-prefix wallet-service | grep '^{' | jq 'select(.correlationId == "my-request-1")'
```

Metrics are in Prometheus format on the management port, 8081, which Compose keeps inside the container: the platform scrapes it, clients never reach it. Postings are counted by type and outcome, refusals by reason, next to Spring Boot's request, JVM and connection pool metrics:

```sh
docker compose exec wallet-service wget -qO- localhost:8081/actuator/prometheus | grep '^wallet_'
```

Tokens for any audience other than `wallet-service`, or from any issuer other than `http://localhost:9000/local`, are rejected with `401`. Tokens name the issuer by the address they were requested at, so request them at `localhost:9000`: a token from `127.0.0.1:9000` names another issuer. Stop everything with `docker compose down -v`.

### Run from source

To run the service from source while developing (JDK 25 required), stop the service container if it runs, start only PostgreSQL and the issuer, and give the service its database, its issuer and the issuer's key set through environment variables; it refuses to start without them:

```sh
docker compose stop wallet-service
docker compose up -d --wait postgres issuer
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet
export SPRING_DATASOURCE_USERNAME=wallet
export SPRING_DATASOURCE_PASSWORD=wallet
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=http://localhost:9000/local
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=http://localhost:9000/local/jwks
./mvnw spring-boot:run
```

Run from source, the service listens on 8080 for the API and 8081 for the actuator (`curl localhost:8081/actuator/prometheus`). If 8081 is taken, set `MANAGEMENT_SERVER_PORT` to another port; the service refuses to start when it is the API's port, since the actuator answers without a token.

### Debugging

With the same environment, start the service with a debug agent and attach the IDE's remote debugger to port 5005:

```sh
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

To debug a test, run it with `./mvnw -Dmaven.surefire.debug -Dtest=WalletServiceTest test`: it waits on port 5005 until the debugger attaches.

A posting or wallet opening runs within a three-second budget the database keeps, a read within five. A breakpoint held longer inside one lets PostgreSQL end the transaction, and the request answers `503`.

### API contract

The service serves its OpenAPI 3.1 contract without a token. The same file is [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml), and every API test checks its exchanges against it:

```sh
curl localhost:8080/openapi.yaml
```

The RFC 9728 metadata every bearer challenge names is public too:

```sh
curl localhost:8080/.well-known/oauth-protected-resource
```

### API collection

[`bruno/`](bruno) holds a [Bruno](https://www.usebruno.com) collection that calls every endpoint in order, the happy path first, then the errors, then the operations endpoints (the probes, the contract and the resource metadata), each request checking the response. Open the folder in the Bruno app and pick the `local` environment, or run it from the command line (Node.js required) with the service up:

```sh
cd bruno && npx -y @usebruno/cli@4.3.0 run --env local -r
```

It can run again and again against the same database. CI runs it against the stack `docker compose` starts, on every pull request and every push to `main`.

## Documentation

- [Requirements](docs/requirements.md): functional and non-functional requirements, assumptions, scope.
- [Design](docs/design.md): architecture, decisions, and how each requirement is met.
- [Roadmap](docs/roadmap.md): increments, their order and the definition of done.
- [Trade-offs](docs/trade-offs.md): compromises, their production path, and time spent.
