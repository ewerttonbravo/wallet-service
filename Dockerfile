# No BuildKit-only syntax: the classic builder must build it too, so Docker alone is enough.
FROM eclipse-temurin:27_35-jdk-alpine-3.24 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Dependencies in a layer of their own, so a change to the code does not download them again.
RUN ./mvnw -B -q dependency:go-offline
COPY src/main src/main
# The tests start containers of their own; CI runs them in the verify job.
RUN ./mvnw -B -q package -Dmaven.test.skip=true \
 && java -Djarmode=tools -jar target/wallet-service-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:27_35-jre-alpine-3.24
RUN addgroup -S wallet && adduser -S -G wallet wallet
WORKDIR /app
# Least to most often changed, so a new build of the code replaces only the last layer.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER wallet
# 8080 serves the API and the probes; 8081 the actuator, for the platform alone.
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
