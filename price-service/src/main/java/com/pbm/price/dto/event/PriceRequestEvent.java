package com.pbm.price.dto.event;

import java.time.Instant;

/**
 * price-topic에서 수신하는 가격 확인 요청 이벤트 Envelope DTO.
 *
 * 역할: command-service 등에서 price-service로 가격 비교 요청을 전달한다.
 * 동작: 이벤트 메타데이터(eventId, eventType, occurredAt, producer)와 함께
 *       payload에 검색 키워드와 목표 가격을 담아 Kafka 메시지로 수신한다.
 * 연관: PriceRequestEventPayload, PriceTopicConsumer.
 */
public record PriceRequestEvent(
        /** 이벤트 고유 식별자 */
        String eventId,
        /** 이벤트 타입 (예: "PRICE_CHECK_REQUEST") */
        String eventType,
        /** 이벤트 발생 시각 */
        Instant occurredAt,
        /** 이벤트 발행 서비스명 */
        String producer,
        /** 가격 확인 요청 페이로드 */
        PriceRequestEventPayload payload
) {
}