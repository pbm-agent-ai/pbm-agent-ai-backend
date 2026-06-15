package com.pbm.price.dto.response;

/**
 * URL 모니터링 활성 태스크 응답 DTO.
 *
 * 역할: command-service의 heartbeat 요청에 응답하여
 *       현재 크롤링 체크 기한이 도래한 URL 모니터링 구독 정보를 전달한다.
 *       익스텐션이 해당 URL을 열어 가격을 수집하고 subscriptionId로 보고할 수 있도록
 *       필요한 모든 정보를 포함한다.
 * 연관: UrlMonitoringController, UrlMonitoringService
 */
public record UrlActiveTaskResponse(
        /** MonitoringSubscription.id - 가격 보고 시 사용 */
        Long subscriptionId,
        /** 크롤링 대상 URL */
        String productUrl,
        /** 목표 가격 (KRW 기준) */
        Integer targetPrice,
        /** 통화 */
        String currency,
        /** 조건 (ALL | ANY) */
        String condition,
        /** 사용자 의도 (AUTO_PURCHASE | PRICE_TRACK) */
        String intent,
        /** 명령 세션 ID */
        String commandId
) {
}
