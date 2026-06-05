package com.pbm.payment.dto.event;

import java.time.Instant;

/**
 * price-service가 발행하는 세션키 등록 요청 이벤트 봉투 DTO.
 * <p>
 * 역할: 신규 AUTO_PURCHASE 모니터링 조건 생성 시 price-service가 이 이벤트를
 *       session-key-registration 토픽으로 발행하고, payment-service가 소비하여
 *       블록체인에 addSessionKey()를 호출한다.
 */
public record SessionKeyRegistrationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        SessionKeyRegistrationEventPayload payload
) {
}
