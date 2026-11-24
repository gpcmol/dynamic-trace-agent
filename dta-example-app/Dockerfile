# Garage demo image, used by the Kubernetes example (deploy/kubernetes). Build from the repository
# root after `mvn -DskipTests package`:
#   docker build -f dta-example-app/Dockerfile -t dynamic-trace-app:latest .
# Pinned to an exact Temurin patch release (25 LTS).
FROM eclipse-temurin:25.0.5.1_2-jre
COPY dta-example-app/target/garage.jar /app/garage.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/garage.jar"]
