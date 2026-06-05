package com.pbm.command.dto.event;

import java.time.Instant;
import java.util.List;

/**
 * product-selection 이벤트의 실제 페이로드 DTO (command-service → price-service).
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 선택한 여러 후보 상품 정보를
 *       price-service로 전달하여 단건 재조회 기반 실시간 검증을 시작하게 한다.
 * 동작: CommandExecutionService.handleProductSelection()이 선택된 상품 목록을 검증한 뒤
 *       이 페이로드를 생성하여 Kafka로 발행한다.
 *       price-service의 ProductSelectionConsumer가 이 데이터를 수신하여
 *       각 상품을 재조회하고 즉시 구매/모니터링 등록 여부를 결정한다.
 * 연관: ProductSelectionEvent, ProductSelectionEventPublisher, ProductSelectionConsumer.
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
        List<ProductCandidateDto> selectedProducts,
        /** 사용자가 지정한 모니터링 마감일 (null이면 price-service가 기본 7일 적용) */
        Instant scheduledEndAt
) {

    public ProductSelectionEventPayload {
        forceResubscribe = Boolean.TRUE.equals(forceResubscribe);
    }
}
