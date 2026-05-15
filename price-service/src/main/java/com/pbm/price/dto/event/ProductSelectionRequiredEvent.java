package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * price-service가 product-selection-required 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: post-search 검증 결과 후보 상품 선택이 필요할 때 command-service로 전달한다.
 * 동작: PRODUCT_SELECTION_REQUIRED 상태일 때 commandId를 포함한
 *       페이로드를 Kafka 메시지로 발행한다.
 * 연관: ProductSelectionRequiredEventPayload, ProductSelectionRequiredEventPublisher,
 *       command-service consumer.
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
