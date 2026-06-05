package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * 구독 종료(취소/만료) 이벤트 봉투 DTO.
 * <p>
 * 역할: price-service가 구독 취소/만료 시 subscription-termination 토픽으로 발행하고,
 *       payment-service가 소비하여 세션키를 DB 업데이트 및 블록체인 revoke 처리한다.
 */
public record SubscriptionTerminationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        SubscriptionTerminationEventPayload payload
) {
}
