package com.pbm.payment.dto.event;

/**
 * 세션키 등록 요청 이벤트 Payload DTO.
 * <p>
 * price-service가 AUTO_PURCHASE 조건 생성 시 발행하며, payment-service가
 * 수신하여 PBMSmartAccount.addSessionKey()를 호출하는 데 필요한 파라미터를 담는다.
 *
 * @param userId            사용자 식별자 (지갑 조회에 사용)
 * @param subscriptionId    모니터링 구독 ID
 * @param aiAgentAddress    AI 에이전트 이더리움 주소
 * @param aiAgentPrivateKey AI 에이전트 개인키 (64자리 hex, DB 저장 및 결제 서명용)
 * @param limitKrw          세션키 한도 (KRW 기준 정수)
 * @param validSeconds      세션키 유효 기간 (초)
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
