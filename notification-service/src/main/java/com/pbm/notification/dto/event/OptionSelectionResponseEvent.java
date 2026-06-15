package com.pbm.notification.dto.event;

import java.time.Instant;

/**
 * 텔레그램에서 사용자가 옵션을 선택한 응답 이벤트.
 * notification-service가 발행하여 command-service가 소비한다.
 */
public record OptionSelectionResponseEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        Payload payload
) {
    public record Payload(
            Long userId,
            String runId,
            String selectedValue
    ) {
    }
}
