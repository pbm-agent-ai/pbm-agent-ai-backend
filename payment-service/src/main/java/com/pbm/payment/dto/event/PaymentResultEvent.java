package com.pbm.payment.dto.event;

import java.time.Instant;

/**
 * payment-service가 payment-result 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 자동결제 처리 결과를 notification-service로 전달한다.
 * 동작: 성공/실패 여부를 eventType과 payload에 담아 Kafka 메시지로 발행한다.
 * 연관: PaymentResultEventPayload, notification-service consumer.
 */
public record PaymentResultEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PaymentResultEventPayload payload
) {
}
