# Usa un'immagine Java ufficiale
FROM eclipse-temurin:21-jdk

# Cartella di lavoro dentro il container
WORKDIR /app

# Copia il file JAR generato da Maven (assicurati di usare il nome corretto)
COPY target/back-0.0.1-SNAPSHOT.jar app.jar

# Espone la porta 8080
EXPOSE 8080

# Comando per avviare l'app Spring Boot
ENTRYPOINT ["java", "-jar", "app.jar"]
