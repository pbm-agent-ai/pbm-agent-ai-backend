package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * 결제 페이지 도달 시 payment-topic으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 브라우저 자동화가 결제 페이지에 도달했을 때 PBM 토큰 차감을 요청한다.
 * 동작: payment-service의 PaymentRequestConsumer가 동일 토픽을 소비하므로
 *       기존 PaymentRequestEvent와 동일한 필드 구조를 사용한다.
 * 연관: CheckoutPaymentEventPayload, CheckoutPaymentEventPublisher.
 */
public record CheckoutPaymentEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        CheckoutPaymentEventPayload payload
) {
}
