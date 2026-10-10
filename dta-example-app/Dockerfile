# Garage demo image, used by the Kubernetes example (deploy/kubernetes). Build from the repository
# root after `mvn -DskipTests package`:
#   docker build -f dta-example-app/Dockerfile -t dynamic-trace-app:latest .
FROM eclipse-temurin:21.0.5_11-jre
COPY dta-example-app/target/garage.jar /app/garage.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/garage.jar"]
