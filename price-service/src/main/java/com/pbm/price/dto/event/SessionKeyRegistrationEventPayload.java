package com.pbm.price.dto.event;

/**
 * 세션키 등록 요청 이벤트 Payload DTO.
 *
 * @param userId            사용자 식별자 (payment-service에서 지갑 주소 조회에 사용)
 * @param subscriptionId    모니터링 구독 ID
 * @param aiAgentAddress    AI 에이전트 이더리움 주소
 * @param aiAgentPrivateKey AI 에이전트 개인키 (64자리 hex, payment-service DB 저장 및 결제 서명용)
 * @param limitKrw          세션키 한도 (KRW 기준 정수, 목표 가격)
 * @param validSeconds      세션키 유효 기간 (초, 모니터링 종료 예정 시각 기준)
 * @param platform          플랫폼 구분 문자열 (예: "NAVER", "ALIEXPRESS")
 */
public record SessionKeyRegistrationEventPayload(
        Long userId,
        Long subscriptionId,
        String aiAgentAddress,
        String aiAgentPrivateKey,
        Long limitKrw,
        Long validSeconds,
        String platform
) {
}
