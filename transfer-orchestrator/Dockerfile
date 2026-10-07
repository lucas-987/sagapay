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
# Optional Maven mirror, for builds without direct access to Maven Central.
ARG MAVEN_MIRROR_URL=
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
# Dependencies resolve in their own layer, so a source change does not download them again.
RUN mvn -q -B -pl transfer-orchestrator -am dependency:go-offline -DexcludeGroupIds=dev.treyer.sagapay
COPY . .
RUN mvn -q -pl transfer-orchestrator -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/transfer-orchestrator/target/transfer-orchestrator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
