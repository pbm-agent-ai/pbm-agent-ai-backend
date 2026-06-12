package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * 로그인 자격증명 요청 이벤트.
 */
public record LoginCredentialRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        LoginCredentialRequestPayload payload
) {
}
