package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.PriceCheckResponse;
import com.pbm.command.service.PriceRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * command-service 요청을 처리하는 컨트롤러.
 *
 * 역할: 가격 확인 요청 API를 받아 Kafka 발행 서비스로 전달한다.
 * 동작: 클라이언트 요청 수신 → 서비스 호출 → 공통 응답 래퍼 반환.
 * 연관: PriceRequestService, PriceCheckRequest, ApiResponse.
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final PriceRequestService priceRequestService;

    public CommandController(PriceRequestService priceRequestService) {
        this.priceRequestService = priceRequestService;
    }

    /**
     * 가격 확인 요청을 받아 Kafka price-topic으로 발행한다.
     *
     * @param request 가격 확인 요청 DTO
     * @return 발행 결과 응답
     */
    @PostMapping("/price-check")
    public ResponseEntity<ApiResponse<PriceCheckResponse>> priceCheck(
            @RequestBody PriceCheckRequest request
    ) {
        PriceCheckResponse response = priceRequestService.publishPriceCheckRequest(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
