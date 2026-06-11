package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * 텔레그램에서 사용자가 옵션을 선택한 응답 이벤트 Envelope DTO.
 *
 * 역할: notification-service가 텔레그램 webhook으로 사용자 응답을 수신한 뒤
 *       command-service에 선택된 옵션 값을 전달한다.
 * 연관: OptionSelectionResponsePayload, OptionSelectionResponseConsumer.
 */
public record OptionSelectionResponseEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        OptionSelectionResponsePayload payload
) {
}
