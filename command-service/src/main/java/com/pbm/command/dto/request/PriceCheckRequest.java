package com.pbm.command.dto.request;

/**
 * 가격 확인 요청 DTO.
 *
 * 역할: 클라이언트가 command-service로 보내는 가격 확인 요청 데이터를 담는다.
 * 동작: 사용자 ID, 검색 키워드, 목표 가격을 받아 service 계층으로 전달한다.
 * 연관: CommandController, PriceRequestService.
 */
public record PriceCheckRequest(
        Long userId,
        String keyword,
        Integer targetPrice
) {
}
