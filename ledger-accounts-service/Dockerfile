# syntax=docker/dockerfile:1
#
# Built from the repository root: the Maven reactor needs every module.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/
COPY contracts/pom.xml contracts/
COPY ledger-accounts-service/pom.xml ledger-accounts-service/
COPY transfer-orchestrator/pom.xml transfer-orchestrator/
COPY fraud-detection-service/pom.xml fraud-detection-service/
COPY api-gateway/pom.xml api-gateway/
COPY notification-consumer/pom.xml notification-consumer/
# Dependencies resolve in their own layer, so a source change does not download them again.
RUN mvn -q -B -pl ledger-accounts-service -am dependency:go-offline -DexcludeGroupIds=dev.treyer.sagapay
COPY . .
RUN mvn -q -pl ledger-accounts-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/ledger-accounts-service/target/ledger-accounts-service-*.jar app.jar
EXPOSE 8081 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
