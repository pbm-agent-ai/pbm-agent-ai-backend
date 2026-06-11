package com.pbm.command.dto.response;

/**
 * URL 모니터링 태스크 응답 DTO.
 *
 * 역할: heartbeat 응답에 포함되어 익스텐션에 모니터링 대상 URL과 조건을 전달한다.
 *       price-service의 MonitoringSubscription을 price-service가 직접 매핑하여
 *       PriceServiceClient를 통해 command-service로 전달된다.
 *       taskId는 subscriptionId와 동일한 값을 사용한다 (url_monitoring_tasks 테이블 제거 후).
 * 연관: BrowserHeartbeatResponse, PriceServiceClient
 */
public record UrlMonitoringTaskResponse(
        Long taskId,
        Long subscriptionId,
        String productUrl,
        Integer targetPrice,
        String currency,
        String condition,   // "ALL" | "ANY"
        String intent,
        String commandId
) {
}
