package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * 상품 옵션 선택 요청 이벤트 Envelope DTO.
 *
 * 역할: 상품 상세페이지에서 옵션(색상/사이즈 등)이 존재하나 사용자가 지정하지 않았을 때
 *       notification-service에 텔레그램 옵션 선택 메시지 발송을 요청한다.
 * 연관: OptionSelectionRequestPayload, OptionSelectionEventPublisher.
 */
public record OptionSelectionRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        OptionSelectionRequestPayload payload
) {
}
