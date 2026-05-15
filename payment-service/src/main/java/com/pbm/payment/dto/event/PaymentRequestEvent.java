package com.pbm.payment.dto.event;

import java.time.Instant;

/**
 * payment-service가 payment-topic 토픽에서 수신하는 이벤트 Envelope DTO.
 *
 * 역할: price-service가 발행한 자동결제 실행 요청의 메타데이터와 payload를 함께 담는다.
 * 동작: Kafka consumer가 이 DTO로 메시지를 역직렬화한 뒤, payload를 PaymentService로 전달한다.
 * 연관: PaymentRequestEventPayload, PaymentRequestConsumer.
 */

/**
 * PaymentRequestEvent(봉투)와 PaymentRequestEventPayload(내용물)로 분리한 이유
 * 봉투 정보는 모든 이벤트가 공통으로 가져야 할 메타데이터이다.
 */
public record PaymentRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PaymentRequestEventPayload payload
) {
}
