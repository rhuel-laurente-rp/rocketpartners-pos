FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
