package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * price-service가 session-key-registration 토픽으로 발행하는 세션키 등록 요청 이벤트 봉투 DTO.
 * <p>
 * AUTO_PURCHASE intent의 신규 모니터링 구독 생성 시 발행된다.
 * payment-service가 수신하여 PBMSmartAccount.addSessionKey()를 호출한다.
 */
public record SessionKeyRegistrationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        SessionKeyRegistrationEventPayload payload
) {
}
