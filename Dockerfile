# Use Maven to build the JAR, then use JRE to run it
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=builder /app/target/companyScraper-1.0.0.jar app.jar
RUN mkdir -p /app/uploads /app/csv_backups /app/logs
RUN chmod -R 755 /app/uploads /app/csv_backups /app/logs
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SPRING_PROFILES_ACTIVE=prod
USER 1001
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
