package com.pbm.notification.dto.event;

import java.time.Instant;

/**
 * 로그인 자격증명 응답 이벤트 (notification-service에서 발행).
 * command-service가 소비하여 대기 중인 로그인 자동화를 재개한다.
 */
public record LoginCredentialResponseEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        Payload payload
) {
    public record Payload(
            Long userId,
            String runId,
            String username,
            String password
    ) {
    }
}
