# --- Build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

# --- Run ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cherrytrack
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER cherrytrack
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health/liveness HTTP/1.0\r\n\r\n" >&3 && grep -q "UP" <&3' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
