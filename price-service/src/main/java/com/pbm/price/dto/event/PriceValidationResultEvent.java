package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * 선택 상품 검증 결과를 command-service로 전달하는 이벤트 Envelope DTO.
 *
 * 역할: 다중 선택 상품 검증 결과를 command-service에 되돌려 세션 상태와
 *       검증 결과 응답을 업데이트하게 한다.
 * 동작: ProductSelectionConsumer가 검증/구독 등록/결제 정책 판단을 마친 뒤 이 이벤트를 발행한다.
 * 연관: PriceValidationResultEventPayload, command-service consumer.
 */
public record PriceValidationResultEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PriceValidationResultEventPayload payload
) {
}
