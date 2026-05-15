package com.pbm.command.dto.event;

/**
 * product-selection-required 시 후보 상품 정보를 전달하는 DTO.
 *
 * 역할: price-service가 Kafka 이벤트로 전달한 후보 상품 목록을
 *       command-service에서 역직렬화할 때 사용한다.
 * 동작: ProductSelectionRequiredConsumer가 이 DTO 리스트를 추출하여
 *       CommandSession 엔티티에 JSON 직렬화 후 저장한다.
 * 연관: ProductSelectionRequiredEventPayload, ProductSelectionRequiredConsumer,
 *       CommandSessionService.
 */
public record ProductCandidateDto(
        /** 상품 식별자 (플랫폼 상품 ID 또는 productUrl 기반 폴백 식별자) */
        String productId,
        /** 상품명 */
        String title,
        /** 최저가 문자열 */
        String lprice,
        /** 쇼핑몰 이름 */
        String mallName,
        /** 상품 상세 링크 */
        String productUrl,
        /** 통화 코드 */
        String currency,
        /** 플랫폼 코드 */
        String platform,
        /** 검색 키워드 */
        String searchKeyword
) {
}
