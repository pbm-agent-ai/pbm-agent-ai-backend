package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * price-service가 price-alert 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 목표 가격 충족 결과를 알림 서비스로 전달한다.
 * 동작: 가격 비교 결과가 조건을 만족하면 eventType과 payload를 함께 Kafka 메시지로 발행한다.
 * 연관: PriceAlertEventPayload, notification-service consumer.
 */
public record PriceAlertEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PriceAlertEventPayload payload
) {
}
