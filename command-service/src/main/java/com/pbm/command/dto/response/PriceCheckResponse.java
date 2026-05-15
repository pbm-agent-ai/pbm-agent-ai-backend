package com.pbm.command.dto.response;

/**
 * 가격 확인 요청 발행 결과 응답 DTO.
 *
 * 역할: command-service가 price-topic으로 메시지를 발행한 뒤 반환할 결과를 담는다.
 * 동작: 생성된 eventId, 발행 topic, 결과 메시지를 응답으로 전달한다.
 * 연관: CommandExecutionService, PriceRequestService.
 */

public record PriceCheckResponse(
        String eventId,
        String topic,
        String message
) {
}
