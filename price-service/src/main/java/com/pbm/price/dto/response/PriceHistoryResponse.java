package com.pbm.price.dto.response;

import java.util.List;

/**
 * 가격 히스토리 조회 API 응답 DTO.
 *
 * 프론트엔드 차트 렌더링에 필요한 통계 + 시계열 데이터를 포함한다.
 *
 * @param subscriptionId  구독 ID (프론트에서 conditionId로 사용)
 * @param keyword         검색 키워드 또는 상품명
 * @param platform        플랫폼 (NAVER, ALIEXPRESS, URL)
 * @param maxPrice        사용자 목표 가격
 * @param currentPrice    가장 최근 수집된 가격
 * @param lowestPrice     기간 내 최저가
 * @param highestPrice    기간 내 최고가
 * @param averagePrice    기간 내 평균가
 * @param priceHistory    시계열 가격 이력
 * @param totalElements   전체 이력 건수
 */
public record PriceHistoryResponse(
        Long subscriptionId,
        String keyword,
        String platform,
        Integer maxPrice,
        Integer currentPrice,
        Integer lowestPrice,
        Integer highestPrice,
        Integer averagePrice,
        List<PriceHistoryEntryResponse> priceHistory,
        Integer totalElements
) {
    /**
     * 개별 가격 이력 항목.
     *
     * @param price        수집된 현재 가격
     * @param originalPrice 할인 전 원가 (없으면 price와 동일)
     * @param currency     통화 (KRW, USD)
     * @param isLowestPrice 기간 내 최저가 여부
     * @param source       수집 출처 (API, EXTENSION)
     * @param productUrl   상품 URL
     * @param collectedAt  수집 시각 (ISO 8601)
     */
    public record PriceHistoryEntryResponse(
            Integer price,
            Integer originalPrice,
            String currency,
            boolean isLowestPrice,
            String source,
            String productUrl,
            String collectedAt
    ) {
    }
}
