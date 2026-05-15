package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * command-service가 product-selection 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 여러 후보 상품을 선택했을 때
 *       price-service로 전달하여 실시간 단건 재조회 검증을 시작하도록 한다.
 * 동작: eventType은 PRODUCTS_SELECTED로 설정하고, payload에 선택된 상품 목록과
 *       공통 명령 메타데이터를 담아 Kafka 메시지로 발행한다.
 *       price-service의 ProductSelectionConsumer가 이 이벤트를 수신하여 처리한다.
 * 연관: ProductSelectionEventPayload, ProductSelectionEventPublisher, ProductSelectionConsumer.
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
