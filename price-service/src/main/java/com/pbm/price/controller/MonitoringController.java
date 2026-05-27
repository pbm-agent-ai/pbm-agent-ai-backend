package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.request.MonitoringSubscriptionUpdateRequest;
import com.pbm.price.dto.response.MonitoringSubscriptionResponse;
import com.pbm.price.service.MonitoringSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 모니터링 구독 조회 컨트롤러.
 *
 * 역할: JWT 인증 사용자의 현재 모니터링 중인 상품 목록을 반환한다.
 * 동작: Gateway가 검증한 X-User-Id 헤더를 받아 ACTIVE 상태의 구독 목록을 조회한다.
 * 연관: MonitoringSubscriptionService, MonitoringSubscriptionResponse.
 */
@Slf4j
@Tag(name = "Monitoring", description = "모니터링 구독 조회 API")
@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringSubscriptionService monitoringSubscriptionService;

    /**
     * 현재 사용자의 ACTIVE 상태 모니터링 상품 목록을 반환한다.
     *
     * @param userIdHeader Gateway가 주입한 사용자 ID (X-User-Id)
     * @return 모니터링 중인 상품 목록
     */
    @Operation(
            summary = "모니터링 상품 목록 조회",
            description = "현재 사용자가 등록한 ACTIVE 상태의 모니터링 구독 목록을 반환합니다."
    )
    @GetMapping("/subscriptions")
    public ApiResponse<List<MonitoringSubscriptionResponse>> getActiveSubscriptions(
            @Parameter(hidden = true)
            @RequestHeader("X-User-Id") Long userIdHeader
    ) {
        log.info("모니터링 구독 목록 조회 - userId: {}", userIdHeader);

        List<MonitoringSubscriptionResponse> result = monitoringSubscriptionService
                .findActiveByUserId(userIdHeader)
                .stream()
                .map(MonitoringSubscriptionResponse::from)
                .toList();

        return ApiResponse.success(result, "모니터링 목록 조회 성공 (총 " + result.size() + "건)");
    }

    /**
     * 모니터링 구독 조건을 부분 수정한다.
     * <p>
     * 수정 가능 항목:
     * - intent       : 알림(PRICE_TRACK) ↔ 자동 결제(AUTO_PURCHASE) 전환
     * - targetPrice  : 목표 가격 변경
     * - scheduledEndAt : 모니터링 종료 예정 시각 변경
     * null인 필드는 현재 값을 유지한다.
     *
     * @param subscriptionId 수정할 구독 ID
     * @param userIdHeader   Gateway가 주입한 사용자 ID
     * @param request        수정 요청 DTO
     * @return 수정된 구독 정보
     */
    @Operation(
            summary = "모니터링 조건 수정",
            description = "intent(알림/자동결제), targetPrice(목표가격), scheduledEndAt(종료일) 중 하나 이상을 수정합니다. null 필드는 유지됩니다."
    )
    @PatchMapping("/subscriptions/{id}")
    public ApiResponse<MonitoringSubscriptionResponse> updateSubscription(
            @PathVariable("id") Long subscriptionId,
            @Parameter(hidden = true)
            @RequestHeader("X-User-Id") Long userIdHeader,
            @RequestBody MonitoringSubscriptionUpdateRequest request
    ) {
        log.info("모니터링 구독 수정 요청 - subscriptionId: {}, userId: {}", subscriptionId, userIdHeader);

        MonitoringSubscriptionResponse result = MonitoringSubscriptionResponse.from(
                monitoringSubscriptionService.updateSubscription(userIdHeader, subscriptionId, request)
        );

        return ApiResponse.success(result, "모니터링 조건 수정 성공");
    }

    /**
     * 모니터링 구독을 취소한다 (상태를 CANCELLED로 변경).
     * <p>
     * DB에서 실제 삭제하지 않고 상태를 CANCELLED로 변경하여
     * 이력을 보존하면서 모니터링을 중단한다.
     *
     * @param subscriptionId 취소할 구독 ID
     * @param userIdHeader   Gateway가 주입한 사용자 ID
     */
    @Operation(
            summary = "모니터링 구독 취소",
            description = "지정한 구독의 상태를 CANCELLED로 변경하여 모니터링을 중단합니다."
    )
    @DeleteMapping("/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSubscription(
            @PathVariable("id") Long subscriptionId,
            @Parameter(hidden = true)
            @RequestHeader("X-User-Id") Long userIdHeader
    ) {
        log.info("모니터링 구독 취소 요청 - subscriptionId: {}, userId: {}", subscriptionId, userIdHeader);
        monitoringSubscriptionService.cancelSubscription(userIdHeader, subscriptionId);
    }
}
