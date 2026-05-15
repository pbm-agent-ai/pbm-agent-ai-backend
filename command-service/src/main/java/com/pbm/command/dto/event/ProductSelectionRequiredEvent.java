package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * price-service가 product-selection-required 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: post-search 검증 결과 후보 상품 선택이 필요할 때 command-service에서 수신한다.
 * 동작: Kafka 메시지로 수신하여 CommandSessionService.updateToProductSelectionRequired()을
 *       호출함으로써 CommandSession 상태를 PRODUCT_SELECTION_REQUIRED으로 전환한다.
 * 연관: ProductSelectionRequiredEventPayload, ProductSelectionRequiredConsumer,
 *       CommandSessionService.
 */
public record ProductSelectionRequiredEvent(
        /** 이벤트 고유 식별자 */
        String eventId,
        /** 이벤트 타입 (예: "PRODUCT_SELECTION_REQUIRED") */
        String eventType,
        /** 이벤트 발생 시각 */
        Instant occurredAt,
        /** 이벤트 발행 서비스명 */
        String producer,
        /** product-selection-required 페이로드 */
        ProductSelectionRequiredEventPayload payload
) {
}
