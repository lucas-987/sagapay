# syntax=docker/dockerfile:1
#
# Build from the repo root (context = ..), not from this directory alone: the
# Maven reactor needs to see every module (common, contracts, the other services)
# to resolve the multi-module build, even though only this one is actually built
# (-pl transfer-orchestrator -am).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl transfer-orchestrator -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/transfer-orchestrator/target/transfer-orchestrator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
