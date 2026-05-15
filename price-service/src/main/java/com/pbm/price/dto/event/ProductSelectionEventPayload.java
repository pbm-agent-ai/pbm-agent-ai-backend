package com.pbm.price.dto.event;

import java.util.List;

/**
 * product-selection 이벤트의 실제 페이로드 DTO (command-service → price-service, consumer 측).
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 선택한 여러 후보 상품 정보를
 *       command-service로부터 전달받아 실시간 단건 재조회 검증을 시작하는 데 사용한다.
 * 동작: command-service의 ProductSelectionEventPublisher가 발행한 이벤트를
 *       Kafka로 수신하여 역직렬화할 때 이 DTO를 사용한다.
 *       ProductSelectionConsumer가 이 페이로드를 추출하여
 *       선택 상품 각각을 재조회한 뒤 즉시 구매/모니터링 등록 여부를 판정한다.
 * 연관: ProductSelectionEvent, ProductSelectionConsumer, PriceTopicConsumer.
 */
public record ProductSelectionEventPayload(
        /** 명령 고유 식별자 (commandId, UUID 문자열) */
        String commandId,
        /** 요청 사용자 ID */
        Long userId,
        /** 사용자가 설정한 목표 가격 (원) */
        Integer targetPrice,
        /** 사용자 의도 (예: "AUTO_PURCHASE", "PRICE_CHECK") */
        String intent,
        /** 기존 구독 갱신/재시작을 사용자가 명시적으로 허용했는지 여부 */
        Boolean forceResubscribe,
        /** 사용자가 선택한 후보 상품 목록 */
        List<ProductCandidateDto> selectedProducts
) {

    public ProductSelectionEventPayload {
        forceResubscribe = Boolean.TRUE.equals(forceResubscribe);
    }
}
