package com.pbm.notification.dto.event;

/**
 * price-alert 토픽의 실제 가격 알림 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 가격 조건 충족 알림을 생성할 때 필요한 최소 정보를 전달한다.
 * 동작: price-service가 목표 가격 도달을 감지하면 공통 이벤트 Envelope(PriceAlertEvent) 안에
 *       이 payload를 담아 발행하고, notification-service 컨슈머가 이 필드들을 읽어 알림을 구성한다.
 * 연관: PriceAlertEvent, PriceAlertConsumer.
 *
 * 필드 설명:
 * - userId:       알림을 받을 사용자 ID (누구의 목표 가격인가?)
 * - productName:  상품명 (예: "아이폰 16 Pro 256GB")
 * - currentPrice: 현재 가격 (단위: 원, 정수)
 * - targetPrice:  사용자가 설정한 목표 가격 (단위: 원, 정수)
 * - productUrl:   상품 페이지 URL (알림에서 바로 이동할 수 있도록)
 */
public record PriceAlertEventPayload(
        Long userId,
        String productName,
        Integer currentPrice,
        Integer targetPrice,
        String productUrl
) {
}