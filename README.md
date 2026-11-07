# Wallet Service

[![CI](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ewerttonbravo/wallet-service/actions/workflows/ci.yml)

A wallet microservice that manages users' money: wallet creation, deposits, withdrawals, transfers between users, and current and historical balance retrieval, with full traceability of every operation for auditing.

Built with Java 25 and Spring Boot, following test-driven development and a clean separation between the domain and the technology around it.

## Status

Work in progress. Installation, usage, and design documentation will be added as the service is built.

## Build and test

Requires JDK 25. The Maven Wrapper downloads Maven on first use.

```sh
./mvnw verify
```

The build fails if line or branch coverage drops below 100%, or if the domain depends on any framework.

## Documentation

- [Requirements](docs/requirements.md): functional and non-functional requirements, assumptions, scope.
- [Design](docs/design.md): architecture, decisions, and how each requirement is met.
- [Roadmap](docs/roadmap.md): increments, their order and the definition of done.
- [Trade-offs](docs/trade-offs.md): compromises, their production path, and time spent.
