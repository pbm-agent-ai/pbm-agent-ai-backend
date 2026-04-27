# PBM Eureka Server - 멀티스테이지 빌드
# 1단계: Gradle로 JAR 빌드
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
# 의존성 캐시를 위해 먼저 빌드 설정만 복사
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY eureka-server/ ./eureka-server/
RUN ./gradlew --no-daemon :eureka-server:bootJar

# 2단계: 런타임 이미지
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/eureka-server/build/libs/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]