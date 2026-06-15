package com.pbm.notification.dto.event;

import java.time.Instant;

/**
 * 로그인 자격증명 요청 이벤트 (command-service에서 발행).
 * notification-service가 소비하여 텔레그램으로 아이디/비밀번호 입력을 요청한다.
 */
public record LoginCredentialRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        Payload payload
) {
    public record Payload(
            Long userId,
            String runId,
            String productName
    ) {
    }
}
