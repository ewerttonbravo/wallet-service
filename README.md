# Wallet Service

[![CI](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml)

A microservice that keeps users' wallets: it opens wallets, takes deposits and withdrawals, moves funds from one user to another, and reports current and past balances. Each operation is recorded in an append-only ledger, so any balance can be audited and rebuilt.

Built with Java 25 and Spring Boot, following test-driven development and a clean separation between the domain and the technology around it.

## Status

Work in progress. Installation, usage, and design documentation will be added as the service is built.

## Build and test

Requires JDK 25 and a running Docker daemon: integration tests start their own PostgreSQL with Testcontainers. The Maven Wrapper downloads Maven on first use.

```sh
./mvnw verify
```

The build fails if line or branch coverage drops below 100%, or if the domain or application layers depend on any framework.

With Colima instead of Docker Desktop, point Testcontainers to its socket first:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Run

The service needs a PostgreSQL database and a JWT issuer, configured through environment variables. It refuses to start without them.

Start a local database:

```sh
docker run -d --name wallet-db -p 5432:5432 \
  -e POSTGRES_DB=wallet -e POSTGRES_USER=wallet -e POSTGRES_PASSWORD=wallet \
  postgres:18-alpine
```

Then start the service:

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet
export SPRING_DATASOURCE_USERNAME=wallet
export SPRING_DATASOURCE_PASSWORD=wallet
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://your-issuer.example
./mvnw spring-boot:run
```

Flyway creates the schema on startup. Check that the service is up with `curl localhost:8080/actuator/health`.

The issuer above is a placeholder: the service starts, health endpoints answer, and every other endpoint returns `401`. To call the API, set the issuer to a real OpenID Connect provider whose tokens you can obtain. A one-command setup with the database and a local token issuer is planned (see the [roadmap](docs/roadmap.md)).

## Documentation

- [Requirements](docs/requirements.md): functional and non-functional requirements, assumptions, scope.
- [Design](docs/design.md): architecture, decisions, and how each requirement is met.
- [Roadmap](docs/roadmap.md): increments, their order and the definition of done.
- [Trade-offs](docs/trade-offs.md): compromises, their production path, and time spent.
