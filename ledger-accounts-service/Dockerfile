# syntax=docker/dockerfile:1
#
# Build from the repo root (context = ..), not from this directory alone: the
# Maven reactor needs to see every module (common, contracts, the other services)
# to resolve the multi-module build, even though only this one is actually built
# (-pl ledger-accounts-service -am).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl ledger-accounts-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/ledger-accounts-service/target/ledger-accounts-service-*.jar app.jar
EXPOSE 8081 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
