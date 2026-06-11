package com.pbm.price.dto.event;

/**
 * price-alert 토픽의 실제 가격 알림 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 가격 조건 충족 알림을 만들 때 필요한 최소 정보를 전달한다.
 * 동작: price-service가 목표 가격 도달을 감지하면 공통 이벤트 Envelope 안에 이 payload를 담아 발행한다.
 * 연관: PriceAlertEvent, scheduler, notification-service consumer.
 *
 * @param userId        사용자 식별자
 * @param productName   상품명
 * @param currentPrice  현재 가격 (KRW)
 * @param targetPrice   목표 가격 (KRW)
 * @param productUrl    상품 상세 페이지 URL
 * @param searchKeyword 검색 키워드
 * @param intent        구독 의도 ("MONITORING" 또는 "AUTO_PURCHASE")
 */
public record PriceAlertEventPayload(
        Long userId,
        String productName,
        Integer currentPrice,
        Integer targetPrice,
        String productUrl,
        String searchKeyword,
        String intent
) {
}
