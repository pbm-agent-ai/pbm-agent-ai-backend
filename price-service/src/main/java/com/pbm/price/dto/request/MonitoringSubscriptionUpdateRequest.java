package com.pbm.price.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 모니터링 구독 조건 수정 요청 DTO.
 *
 * 역할: PATCH 요청으로 전달받는 수정 가능한 3가지 조건을 담는다.
 * 동작: null 필드는 수정하지 않는다 (부분 수정 PATCH 방식).
 *       세 필드 모두 null이면 유효하지 않은 요청으로 서비스에서 거부한다.
 * 연관: MonitoringController, MonitoringSubscriptionService.
 *
 * intent 허용값:
 *   - "AUTO_PURCHASE" : 목표가 도달 시 자동 결제
 *   - "PRICE_TRACK"   : 목표가 도달 시 알림만 전송
 */
public record MonitoringSubscriptionUpdateRequest(

        /**
         * 알림/자동 결제 옵션.
         * "AUTO_PURCHASE" | "PRICE_TRACK" | null(변경 없음)
         */
        String intent,

        /**
         * 목표 가격.
         * null이면 현재 값 유지.
         */
        BigDecimal targetPrice,

        /**
         * 모니터링 종료 예정 시각.
         * null이면 현재 값 유지.
         * ISO-8601 형식: "2026-06-01T00:00:00Z"
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant scheduledEndAt
) {
}
