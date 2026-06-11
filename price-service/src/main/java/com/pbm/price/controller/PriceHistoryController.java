package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.response.PriceHistoryResponse;
import com.pbm.price.service.PriceHistoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가격 히스토리 조회 API 컨트롤러.
 *
 * 역할: 프론트엔드 가격 추이 차트에 필요한 시계열 데이터와 통계를 제공한다.
 * 연관: PriceHistoryQueryService
 */
@Tag(name = "Price History", description = "가격 히스토리 조회 API")
@RestController
@RequestMapping("/api/prices")
public class PriceHistoryController {

    private final PriceHistoryQueryService priceHistoryQueryService;

    public PriceHistoryController(PriceHistoryQueryService priceHistoryQueryService) {
        this.priceHistoryQueryService = priceHistoryQueryService;
    }

    /**
     * 특정 모니터링 구독의 가격 히스토리를 조회한다.
     *
     * @param subscriptionId 모니터링 구독 ID (프론트에서 conditionId로 사용)
     * @param period         조회 기간 (7d, 30d, 90d, null=전체)
     * @return 가격 히스토리 + 통계 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "가격 히스토리 조회",
               description = "모니터링 구독의 기간별 가격 변동 이력과 통계(최저가, 최고가, 평균가)를 반환합니다.")
    @GetMapping("/{subscriptionId}/history")
    public ResponseEntity<ApiResponse<PriceHistoryResponse>> getPriceHistory(
            @PathVariable Long subscriptionId,
            @RequestParam(required = false) String period
    ) {
        PriceHistoryResponse response = priceHistoryQueryService.getPriceHistory(subscriptionId, period);
        return ResponseEntity.ok(ApiResponse.success(response, "가격 히스토리 조회 성공"));
    }
}
