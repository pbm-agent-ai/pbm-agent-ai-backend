# PBM Gateway - 멀티스테이지 빌드
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY gateway/ ./gateway/
RUN ./gradlew --no-daemon :gateway:bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]