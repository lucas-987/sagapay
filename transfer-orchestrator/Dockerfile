# syntax=docker/dockerfile:1
#
# Built from the repository root: the Maven reactor needs every module.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl transfer-orchestrator -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/transfer-orchestrator/target/transfer-orchestrator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
