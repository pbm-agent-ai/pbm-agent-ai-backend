# PBM Price Service - 멀티스테이지 빌드
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY price-service/ ./price-service/
RUN ./gradlew --no-daemon :price-service:bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/price-service/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]