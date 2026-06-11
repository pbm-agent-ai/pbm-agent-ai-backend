package com.pbm.notification.dto.event;

import java.time.Instant;

/**
 * payment-service가 payment-result 토픽으로 발행하는 결제 결과 이벤트 Envelope DTO.
 *
 * 역할: payment-service에서 결제 완료/실패 결과를 Kafka로 발행할 때 사용하는 최상위 래퍼 객체다.
 * 동작: notification-service의 PaymentResultConsumer가 JSON 메시지를 역직렬화할 때
 *       최상위 필드(eventId, eventType, occurredAt, producer, payload)를 이 레코드에 매핑한다.
 *       payload 필드는 실제 결제 결과 데이터를 담는다.
 * 연관: PaymentResultEventPayload, PaymentResultConsumer.
 *
 * 구조 예시:
 * {
 *   "eventId": "evt-xyz789",
 *   "eventType": "PAYMENT_COMPLETED",       // 또는 "PAYMENT_FAILED"
 *   "occurredAt": "2026-04-26T13:00:00Z",
 *   "producer": "payment-service",
 *   "payload": { ... }                       // PaymentResultEventPayload
 * }
 */
public record PaymentResultEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PaymentResultEventPayload payload
) {
}
