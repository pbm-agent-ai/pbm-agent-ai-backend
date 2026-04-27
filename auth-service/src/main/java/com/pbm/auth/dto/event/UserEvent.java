package com.pbm.auth.dto.event;

import java.time.Instant;

/**
 * auth-service가 user-event 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 인증 관련 이벤트를 공통 필드(eventId, eventType, occurredAt, producer)와 함께 Kafka로 전달한다.
 * 동작: 회원가입/로그인 성공 시 payload를 감싸 하나의 이벤트 메시지로 직렬화한다.
 * 연관: UserEventPayload, KafkaTemplate, notification-service consumer.
 */
public record UserEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        UserEventPayload payload
) {
}
