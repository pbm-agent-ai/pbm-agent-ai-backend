package com.pbm.command.dto.event;

import java.time.Instant;

/**
 * command-service가 price-topic 토픽으로 발행하는 이벤트 Envelope DTO.
 *
 * 역할: 자연어 명령에서 해석한 가격 조회/모니터링 요청을 price-service로 전달한다.
 * 동작: eventType으로 조회 요청인지 모니터링 등록인지 구분하고, payload에 필요한 최소 데이터만 담는다.
 * 연관: PriceRequestEventPayload, KafkaTemplate, price-service consumer.
 */
public record PriceRequestEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        PriceRequestEventPayload payload
) {
}
