# PBM Command Service - 멀티스테이지 빌드
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY command-service/ ./command-service/
RUN ./gradlew --no-daemon :command-service:bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/command-service/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]