package com.pbm.payment.dto.event;

import java.time.Instant;

/**
 * 구독 종료(취소/만료) 이벤트 봉투 DTO.
 * <p>
 * price-service가 subscription-termination 토픽으로 발행하는 이벤트.
 * payment-service의 SubscriptionTerminationConsumer가 소비하여 세션키를 처리한다.
 */
public record SubscriptionTerminationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        SubscriptionTerminationEventPayload payload
) {
}
