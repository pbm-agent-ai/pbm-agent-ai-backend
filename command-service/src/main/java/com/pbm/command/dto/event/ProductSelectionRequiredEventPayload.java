package com.pbm.command.dto.event;

import java.util.List;

/**
 * product-selection-required 이벤트의 실제 페이로드 DTO.
 *
 * 역할: price-service가 검증 결과 후보 상품 선택이 필요하다고 판단했을 때,
 *       command-service로 전달할 누락 필드 목록, 후보 상품 목록, 메타 데이터를 담는다.
 * 동작: price-service의 PostSearchValidationResult.missingFields/message를
 *       Kafka 메시지 페이로드로 역직렬화하여 command-service가 처리한다.
 *       candidates에는 사용자 선택이 필요한 후보 상품 목록이 포함된다.
 * 연관: ProductSelectionRequiredEvent, ProductSelectionRequiredConsumer,
 *       CommandSessionService, ProductCandidateDto.
 */
public record ProductSelectionRequiredEventPayload(
        /** 명령 고유 식별자 (commandId, UUID 문자열) */
        String commandId,
        /** 검증을 통해 확정된 카테고리 경로 (없으면 null) */
        String categoryPath,
        /** 누락된 필드명 목록 (예: ["size", "searchResultsCount"]) */
        List<String> missingFields,
        /** 사용자에게 보여줄 보완 요청 문구 */
        String message,
        /** 후보 상품 목록 (검색 결과가 여러 개일 때 사용자 선택을 위해 제공) */
        List<ProductCandidateDto> candidates,
        /** 사용자가 설정한 목표 가격 (원) */
        Integer targetPrice,
        /** 사용자 의도 (예: "AUTO_PURCHASE", "PRICE_CHECK") */
        String intent,
        /** 검색 키워드 */
        String searchKeyword
) {
}
