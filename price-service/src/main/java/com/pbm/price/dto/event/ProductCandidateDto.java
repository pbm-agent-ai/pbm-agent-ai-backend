package com.pbm.price.dto.event;

/**
 * product-selection-required 시 후보 상품 정보를 전달하는 DTO.
 *
 * 역할: 검색 결과 후보가 여러 개일 때 각 상품의 식별자와 기본 정보를
 *       Kafka 이벤트 페이로드에 담아 command-service로 전달한다.
 * 동작: PriceTopicConsumer가 SearchResponse 목록을 ProductCandidateDto로 매핑하여
 *       ProductSelectionRequiredEventPayload.candidates에 포함시킨다.
 * 연관: ProductSelectionRequiredEventPayload, SearchResponse, PriceTopicConsumer.
 */
public record ProductCandidateDto(
        /** 상품 식별자 (플랫폼 상품 ID 또는 productUrl 기반 폴백 식별자) */
        String productId,
        /** 상품명 (HTML 태그 포함 가능) */
        String title,
        /** 최저가 문자열 (예: "250000", "90000.00") */
        String lprice,
        /** 쇼핑몰 이름 */
        String mallName,
        /** 상품 상세 링크 */
        String productUrl,
        /** 대표 상품 이미지 URL */
        String imageUrl,
        /** 통화 코드 ("KRW", "USD" 등) */
        String currency,
        /** 플랫폼 코드 */
        String platform,
        /** 검색 키워드 */
        String searchKeyword
) {
}
