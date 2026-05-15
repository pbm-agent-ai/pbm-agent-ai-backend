package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * price-service가 선택 상품 검증 결과를 command-service로 돌려주는 이벤트 Envelope DTO.
 *
 * 역할: 선택 상품 다중 검증이 끝난 뒤, triggered 상품과 monitoring 상품 목록을
 *       command-session에 반영하기 위한 비동기 결과 메시지를 표현한다.
 * 동작: PriceValidationResultConsumer가 이 이벤트를 수신하여 세션 상태와 검증 결과 JSON을 갱신한다.
 * 연관: PriceValidationResultEventPayload, PriceValidationResultConsumer.
 */
public record PriceValidationResultEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PriceValidationResultEventPayload payload
) {
}
