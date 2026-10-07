# syntax=docker/dockerfile:1
#
# Built from the repository root: the Maven reactor needs every module.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/
COPY contracts/pom.xml contracts/
COPY ledger-accounts-service/pom.xml ledger-accounts-service/
COPY fraud-detection-service/pom.xml fraud-detection-service/
COPY fraud-detection-service/pom.xml fraud-detection-service/
COPY api-gateway/pom.xml api-gateway/
COPY notification-consumer/pom.xml notification-consumer/
# Dependencies resolve in their own layer, so a source change does not download them again.
RUN mvn -q -B -pl fraud-detection-service -am dependency:go-offline -DexcludeGroupIds=dev.treyer.sagapay
COPY . .
RUN mvn -q -pl fraud-detection-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/fraud-detection-service/target/fraud-detection-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
