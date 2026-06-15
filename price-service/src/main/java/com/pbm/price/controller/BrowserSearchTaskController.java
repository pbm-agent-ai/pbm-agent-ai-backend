package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.request.BrowserSearchFailureReportRequest;
import com.pbm.price.dto.request.BrowserSearchResultReportRequest;
import com.pbm.price.dto.response.BrowserSearchActiveTaskResponse;
import com.pbm.price.service.BrowserSearchTaskService;
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

@Tag(name = "Browser Search", description = "브라우저 검색 태스크 API")
@RestController
@RequestMapping("/api/v1/browser-search")
public class BrowserSearchTaskController {

    private final BrowserSearchTaskService browserSearchTaskService;

    public BrowserSearchTaskController(BrowserSearchTaskService browserSearchTaskService) {
        this.browserSearchTaskService = browserSearchTaskService;
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "브라우저 검색 활성 태스크 조회",
            description = "command-service heartbeat 전용 내부 엔드포인트. 브라우저 검색이 필요한 AliExpress 태스크 목록을 반환합니다.")
    @GetMapping("/active-tasks")
    public ResponseEntity<ApiResponse<List<BrowserSearchActiveTaskResponse>>> getActiveTasks(
            @RequestHeader("X-User-Id") Long userId
    ) {
        List<BrowserSearchActiveTaskResponse> tasks = browserSearchTaskService.getActiveTasks(userId);
        browserSearchTaskService.markTasksDispatched(tasks.stream().map(BrowserSearchActiveTaskResponse::taskId).toList());
        return ResponseEntity.ok(ApiResponse.success(tasks, "브라우저 검색 활성 태스크 조회 성공"));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "브라우저 검색 결과 보고",
            description = "익스텐션이 AliExpress 검색 결과 후보 상품 목록을 서버에 보고합니다.")
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<Void>> report(
            @RequestBody BrowserSearchResultReportRequest request
    ) {
        browserSearchTaskService.processSearchResultReport(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "브라우저 검색 실패 보고",
            description = "익스텐션이 AliExpress 검색 실패를 서버에 보고합니다. 태스크를 FAILED로 마킹하여 재디스패치를 중단합니다.")
    @PostMapping("/failure")
    public ResponseEntity<ApiResponse<Void>> reportFailure(
            @RequestBody BrowserSearchFailureReportRequest request
    ) {
        browserSearchTaskService.reportFailure(request.taskId(), request.reason());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
