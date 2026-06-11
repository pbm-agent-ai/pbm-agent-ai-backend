package com.pbm.notification.dto.event;

import java.time.Instant;
import java.util.List;

/**
 * 상품 옵션 선택 요청 이벤트 (command-service에서 발행).
 * notification-service가 소비하여 텔레그램으로 옵션 목록을 전송한다.
 */
public record OptionSelectionRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        Payload payload
) {
    public record Payload(
            Long userId,
            String runId,
            String productName,
            List<OptionGroup> optionGroups
    ) {
    }

    public record OptionGroup(
            String groupName,
            List<String> options
    ) {
    }
}
