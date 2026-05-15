package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * command-service가 product-selection 토픽으로 발행하는 이벤트 Envelope DTO (consumer 측).
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 여러 후보 상품을 선택했을 때
 *       command-service가 발행한 이벤트를 price-service에서 수신한다.
 * 동작: ProductSelectionConsumer가 이 이벤트를 수신하여 각 후보를 단건 재조회하고,
 *       즉시 구매/모니터링 등록/PRICE_CHECK 응답 생성을 수행한다.
 * 연관: ProductSelectionEventPayload, ProductSelectionConsumer, PriceTopicConsumer.
 */
public record ProductSelectionEvent(
        /** 이벤트 고유 식별자 */
        String eventId,
        /** 이벤트 타입 (예: "PRODUCT_SELECTED") */
        String eventType,
        /** 이벤트 발생 시각 */
        Instant occurredAt,
        /** 이벤트 발행 서비스명 */
        String producer,
        /** product-selection 페이로드 */
        ProductSelectionEventPayload payload
) {
}
