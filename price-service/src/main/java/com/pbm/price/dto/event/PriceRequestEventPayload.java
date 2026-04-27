package com.pbm.price.dto.event;

/**
 * price-topic에서 수신하는 가격 확인 요청의 Payload DTO.
 *
 * 역할: command-service 등에서 가격 비교를 요청할 때 필요한 최소 정보를 담는다.
 * 동작: 사용자가 설정한 목표 가격과 검색 키워드를 포함하여 price-service가
 *       상품 검색 후 가격 비교를 수행할 수 있도록 한다.
 * 연관: PriceRequestEvent, PriceTopicConsumer.
 */
public record PriceRequestEventPayload(
        /** 요청 사용자 ID */
        Long userId,
        /** 검색 키워드 (상품명 등) */
        String keyword,
        /** 목표 가격 (원), 현재가가 이 값 이하일 때 알림 발생 */
        Integer targetPrice
) {
}