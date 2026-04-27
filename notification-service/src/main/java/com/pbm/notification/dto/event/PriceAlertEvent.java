package com.pbm.notification.dto.event;

import java.time.Instant;

/**
 * price-alert 토픽에서 수신하는 가격 알림 이벤트 Envelope DTO.
 *
 * 역할: price-service가 발행한 가격 조건 충족 이벤트를 수신할 때 사용하는 래퍼(Envelope) 객체다.
 * 동작: Kafka 컨슈머가 JSON 메시지를 역직렬화할 때 최상위 필드(eventId, eventType, occurredAt, producer, payload)를
 *       이 레코드에 매핑한다. payload 필드는 실제 가격 알림 데이터를 담는다.
 * 연관: PriceAlertEventPayload, PriceAlertConsumer.
 *
 * 구조 예시:
 * {
 *   "eventId": "evt-abc123",              // 이벤트 고유 ID
 *   "eventType": "PRICE_ALERT",            // 이벤트 종류 (가격 알림)
 *   "occurredAt": "2026-04-26T12:00:00Z",  // 이벤트 발생 시각
 *   "producer": "price-service",           // 이벤트 발행 서비스
 *   "payload": { ... }                     // 실제 가격 알림 데이터 (PriceAlertEventPayload)
 * }
 */
public record PriceAlertEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PriceAlertEventPayload payload
) {
}