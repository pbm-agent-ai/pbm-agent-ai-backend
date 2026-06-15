package com.pbm.price.dto.request;

import java.math.BigDecimal;

/**
 * 익스텐션이 URL 페이지에서 수집한 가격/상품 정보를 서버에 보고하는 요청 DTO.
 *
 * 역할: subscriptionId로 해당 구독을 찾고 currentPrice와 targetPrice를 비교한다.
 *       최초 크롤링 시 productName, imageUrl이 함께 전달되면 MonitoringSubscription 스냅샷을 갱신한다.
 * 연관: UrlMonitoringController, UrlMonitoringService
 */
public record UrlPriceReportRequest(
        /** price-service MonitoringSubscription ID */
        Long subscriptionId,
        /** 익스텐션이 페이지에서 추출한 현재 가격 */
        BigDecimal currentPrice,
        /** 통화 (KRW, USD 등) */
        String currency,
        /** og:title 또는 h1에서 추출한 상품명 (최초 크롤링 시에만 유효, null 허용) */
        String productName,
        /** og:image에서 추출한 대표 이미지 URL (null 허용) */
        String imageUrl
) {
    /**
     * 기존 3개 필드 호출부와의 호환성을 위한 보조 생성자.
     */
    public UrlPriceReportRequest(Long subscriptionId, BigDecimal currentPrice, String currency) {
        this(subscriptionId, currentPrice, currency, null, null);
    }
}
