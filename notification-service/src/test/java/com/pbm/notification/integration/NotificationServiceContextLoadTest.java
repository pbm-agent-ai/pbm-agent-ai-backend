package com.pbm.notification.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * notification-service Spring 컨텍스트 로딩 통합 테스트.
 *
 * 역할: 애플리케이션 컨텍스트가 정상적으로 로딩되는지 검증한다.
 * 동작: @SpringBootTest로 전체 빈 컨테이너를 띄우고, H2 인메모리 DB + Embedded Kafka 환경에서
 *       모든 빈(JPA, Kafka, Mail 등)이 정상적으로 생성되는지 확인한다.
 * 연관: NotificationServiceApplication.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "price-alert")
class NotificationServiceContextLoadTest {

    /**
     * Spring Boot 애플리케이션 컨텍스트가 예외 없이 정상 로딩되는지 검증한다.
     */
    @Test
    @DisplayName("컨텍스트 로딩: notification-service가 정상적으로 시작된다")
    void contextLoads() {
        // @SpringBootTest가 애플리케이션 컨텍스트를 정상적으로 띄우면 이 테스트는 통과한다.
    }
}
