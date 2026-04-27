# PBM Notification Service - 멀티스테이지 빌드
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY notification-service/ ./notification-service/
RUN ./gradlew --no-daemon :notification-service:bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/notification-service/build/libs/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]