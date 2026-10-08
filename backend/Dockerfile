FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:922927df2c662cdd47ddb116443d6bec4696cfae3de1a0ddac8fcc7b87ce61ae AS build
WORKDIR /workspace
COPY backend/ ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN addgroup -g 10001 repricer && adduser -D -u 10001 -G repricer repricer
WORKDIR /app
COPY --from=build --chown=repricer:repricer /workspace/app/target/app-0.1.0-SNAPSHOT.jar /app/app.jar
USER repricer
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
