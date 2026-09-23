# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/pr-review-assistant-backend-*.jar /workspace/application.jar

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/application.jar /app/application.jar
USER 65532:65532
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
