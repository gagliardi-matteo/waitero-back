# Stage 1: compila con Maven
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: container finale con solo il jar
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY --from=build /app/target/back-0.0.1-SNAPSHOT.jar app.jar

# Assicurati che uploads/ esista nel container
RUN mkdir -p /app/uploads

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
