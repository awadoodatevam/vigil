# The image of vigil-app; started with SPRING_PROFILES_ACTIVE=migrate it is vigil-migrate (AR-U-01).
# It copies the JAR that Maven built and downloads nothing (BP-R-04).
FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
COPY target/vigil-app.jar /app/vigil-app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/vigil-app.jar"]
