package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * price-service가 payment-topic 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 자동결제 실행 요청을 payment-service로 전달한다.
 * 동작: 결제 대상 상품과 금액 정보를 payload로 담아 Kafka 메시지로 발행한다.
 * 연관: PaymentRequestEventPayload, payment-service consumer.
 */
public record PaymentRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PaymentRequestEventPayload payload
) {
}
