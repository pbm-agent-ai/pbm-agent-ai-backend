package com.pbm.command.dto.event;

/**
 * price-topic 토픽의 실제 가격 요청 데이터를 담는 Payload DTO.
 *
 * 역할: price-service가 가격 조회 또는 모니터링 등록을 시작할 때 필요한 최소 입력값을 전달한다.
 * 동작: command-service가 자연어 파싱 결과를 정리한 뒤 공통 이벤트 Envelope 안에 담아 발행한다.
 * 연관: PriceRequestEvent, command-service parser, price-service consumer.
 */
public record PriceRequestEventPayload(
        Long userId,
        String keyword,
        Integer targetPrice
) {
}
