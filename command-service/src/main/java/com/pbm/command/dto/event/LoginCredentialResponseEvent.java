package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * 로그인 자격증명 응답 이벤트.
 */
public record LoginCredentialResponseEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        LoginCredentialResponsePayload payload
) {
}
