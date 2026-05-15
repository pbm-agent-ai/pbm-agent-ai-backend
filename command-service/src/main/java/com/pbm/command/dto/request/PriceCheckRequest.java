package com.pbm.command.dto.request;

import com.pbm.command.domain.PlatformType;

/**
 * 가격 확인 요청 DTO.
 *
 * 역할: command-service 내부에서 price-topic 발행에 필요한 가격 확인 요청 데이터를 담는다.
 * 동작: 사용자 ID, 검색 키워드, 목표 가격, 플랫폼, 통화 정보를 받아 service 계층으로 전달한다.
 *       platform과 currency는 price-service로 전파되어 가격 비교 대상과 통화 판단에 사용된다.
 * 연관: CommandExecutionService, PriceRequestService.
 */
public record PriceCheckRequest(
        Long userId,
        String keyword,
        Integer targetPrice,
        PlatformType platform,
        String currency
) {
}
