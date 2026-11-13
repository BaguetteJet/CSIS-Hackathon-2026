# The service image. Build the jar first (./check does both):
#   ./gradlew bootJar && docker compose up -d --build
# The image only copies the jar Gradle built, so a Docker build never downloads from Maven Central
# (the 429 trap) and never needs your Forgejo token.
FROM eclipse-temurin:21.0.8_9-jre

WORKDIR /app
COPY build/libs/service.jar app.jar

# Each instance must fit in 512 MB (compose.yml sets the container limit): the heap takes 60% of
# it, the rest is for metaspace, threads and Kafka's buffers. On running out it exits, and Docker
# restarts it, instead of limping on.
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
