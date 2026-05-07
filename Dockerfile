FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app
COPY . .
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=builder /app/target/sysmind-agent-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 4000

ENTRYPOINT ["java", "-jar", "app.jar"]
