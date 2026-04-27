package com.pbm.auth.service;

import com.pbm.auth.domain.User;
import com.pbm.auth.dto.event.UserEvent;
import com.pbm.auth.dto.event.UserEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka 이벤트 발행을 전담하는 비동기 컴포넌트.
 *
 * 역할: 인증 이벤트(로그인 성공 등)를 Kafka 토픽에 비동기로 발행한다.
 * 동작: @Async가 붙은 메서드는 별도 스레드풀에서 실행되므로,
 *       Kafka 브로커가 응답하지 않아도 호출자(로그인/회원가입) 스레드가 블로킹되지 않는다.
 *       발행 실패 시 경고 로그만 남기고 예외를 전파하지 않는다(best-effort).
 * 연관: AuthService, KafkaTemplate, AsyncConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private final KafkaTemplate<String, UserEvent> kafkaTemplate;

    // Kafka user-event 토픽명 (application.yml에서 주입)
    @Value("${app.kafka.topics.user-event}")
    private String userEventTopic;

    /**
     * 로그인 성공 이벤트를 Kafka user-event 토픽으로 비동기 발행한다.
     * - @Async로 별도 스레드에서 실행되어 호출자 스레드를 블로킹하지 않는다.
     * - Kafka 브로커 장애 시에도 로그인 자체는 성공해야 하므로
     *   모든 예외를 잡아 경고 로그만 남긴다(best-effort).
     *
     * @param user 로그인에 성공한 사용자 엔티티
     */
    @Async
    public void publishLoginSuccessEvent(User user) {
        try {
            UserEventPayload payload = new UserEventPayload(
                    user.getId(),
                    user.getEmail(),
                    user.getNickname()
            );
            UserEvent event = new UserEvent(
                    UUID.randomUUID().toString(),   // 이벤트 고유 ID
                    "LOGIN_SUCCESS",                 // 이벤트 타입
                    Instant.now(),                   // 이벤트 발생 시각
                    "auth-service",                  // 이벤트 발행 서비스
                    payload
            );
            kafkaTemplate.send(userEventTopic, String.valueOf(user.getId()), event);
            log.info("로그인 성공 이벤트 발행: userId={}, eventId={}", user.getId(), event.eventId());
        } catch (Exception e) {
            // Kafka 장애가 로그인 자체를 실패시키지 않도록 예외를 삼키고 경고 로그만 남긴다
            log.warn("로그인 성공 이벤트 발행 실패: userId={}, 원인={}", user.getId(), e.getMessage());
        }
    }
}