# The image of demo-control (AR-U-06): the JAR that Maven built, with the demo data and the demo installation's values
# inside it (AR-X-04, AR-X-05). It copies the JAR and downloads nothing (BP-R-04).
FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
COPY target/demo-control.jar /app/demo-control.jar
RUN mkdir -p /var/lib/demo-control && chown 10001:10001 /var/lib/demo-control
USER 10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/demo-control.jar"]
