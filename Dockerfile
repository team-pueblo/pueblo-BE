FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
COPY ${SERVICE}/build/libs/*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
