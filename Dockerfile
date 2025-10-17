FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY target/companyScraper-1.0.0.jar app.jar
RUN mkdir -p /app/uploads /app/csv_backups /app/logs
RUN chmod -R 755 /app/uploads /app/csv_backups /app/logs
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SPRING_PROFILES_ACTIVE=prod
USER 1001
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
