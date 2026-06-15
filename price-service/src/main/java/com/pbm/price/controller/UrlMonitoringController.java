package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.request.UrlPriceReportRequest;
import com.pbm.price.dto.response.UrlActiveTaskResponse;
import com.pbm.price.service.UrlMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * URL 직접 입력 모니터링 API 컨트롤러.
 *
 * 역할: 익스텐션이 URL 페이지에서 수집한 가격을 서버에 보고하는 엔드포인트를 제공한다.
 * 연관: UrlMonitoringService
 */
@Tag(name = "URL Monitoring", description = "URL 직접 입력 모니터링 가격 보고 API")
@RestController
@RequestMapping("/api/v1/url-monitoring")
public class UrlMonitoringController {

    private final UrlMonitoringService urlMonitoringService;

    public UrlMonitoringController(UrlMonitoringService urlMonitoringService) {
        this.urlMonitoringService = urlMonitoringService;
    }

    /**
     * 익스텐션이 URL 페이지에서 수집한 가격을 서버에 보고한다.
     * <p>
     * 동작:
     * 1. subscriptionId로 구독을 찾아 목표 가격과 비교한다.
     * 2. 조건 충족 시 기존 구매/알림 흐름으로 연결한다.
     * 3. ANY 조건이면 같은 commandId 그룹의 나머지 구독을 취소한다.
     * <p>
     * 호출 주체: 익스텐션 (DEVICE 토큰 사용).
     * Gateway는 DEVICE 토큰에 X-User-Id를 주입하지 않으므로 헤더 파라미터를 사용하지 않고,
     * subscriptionId로 구독을 조회하여 소유자를 확인한다.
     *
     * @param request 가격 보고 요청 DTO
     */
    /**
     * command-service heartbeat가 호출하는 내부 엔드포인트.
     * 크롤링 체크 기한이 도래한 URL 모니터링 구독 목록을 반환한다.
     * <p>
     * 호출 주체: command-service (서비스 간 내부 통신, Eureka 로드밸런싱 경유).
     * X-User-Id는 command-service가 heartbeat 디바이스 소유자로부터 직접 주입한다.
     *
     * @param userId command-service가 주입하는 사용자 ID
     * @return 크롤링 대상 URL 태스크 목록
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "URL 모니터링 활성 태스크 조회",
               description = "command-service heartbeat 전용 내부 엔드포인트. 크롤링 기한 도래한 URL 구독 목록을 반환합니다.")
    @GetMapping("/active-tasks")
    public ResponseEntity<ApiResponse<List<UrlActiveTaskResponse>>> getActiveTasks(
            @RequestHeader("X-User-Id") Long userId
    ) {
        List<UrlActiveTaskResponse> tasks = urlMonitoringService.getActiveUrlTasks(userId);
        return ResponseEntity.ok(ApiResponse.success(tasks, "URL 모니터링 활성 태스크 조회 성공"));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "URL 모니터링 가격 보고",
               description = "익스텐션이 URL 페이지에서 수집한 현재 가격을 서버에 보고합니다.")
    @PostMapping("/price-report")
    public ResponseEntity<ApiResponse<Void>> reportPrice(
            @RequestBody UrlPriceReportRequest request
    ) {
        urlMonitoringService.processReport(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
