package com.pbm.payment.controller;

import com.pbm.payment.common.ApiResponse;
import com.pbm.payment.dto.response.PaymentDetailResponse;
import com.pbm.payment.dto.response.PaymentSummaryResponse;
import com.pbm.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PBM 결제 내역 조회 API 컨트롤러.
 * 
 * 역할: 사용자별 결제 목록 조회와 결제 건 단건 상세 조회를 제공한다.
 * 동작: gateway의 /api/v1/payments/** 경로로 라우팅되며,
 *       모든 응답은 ApiResponse<T> 래퍼로 감싸 반환한다.
 * 연관: PaymentService, PaymentSummaryResponse, PaymentDetailResponse.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 특정 사용자의 결제 내역 목록을 최신순으로 조회한다.
     * 
     * @param userId 조회할 사용자 ID (필수)
     * @return 사용자의 결제 요약 목록을 감싼 ApiResponse
     */
    @GetMapping
    public ApiResponse<List<PaymentSummaryResponse>> getPaymentsByUserId(
            @RequestParam Long userId
    ) {
        log.info("결제 목록 조회 요청 - userId: {}", userId);

        List<PaymentSummaryResponse> payments = paymentService.getPaymentsByUserId(userId)
                .stream()
                .map(PaymentSummaryResponse::from)
                .toList();

        return ApiResponse.success(payments, "결제 목록 조회 성공");
    }

    /**
     * 결제 식별자(paymentId)로 결제 건의 상세 정보를 조회한다.
     * 
     * @param paymentId 조회할 결제 식별자 ("pay-" 로 시작하는 문자열)
     * @return 결제 상세 정보를 감싼 ApiResponse, 존재하지 않으면 error 응답
     */
    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentDetailResponse> getPaymentByPaymentId(
            @PathVariable String paymentId
    ) {
        log.info("결제 상세 조회 요청 - paymentId: {}", paymentId);

        try {
            PaymentDetailResponse payment = PaymentDetailResponse.from(
                    paymentService.getPaymentByPaymentId(paymentId)
            );
            return ApiResponse.success(payment, "결제 상세 조회 성공");
        } catch (IllegalArgumentException e) {
            log.warn("결제 건 조회 실패 - paymentId: {}, 원인: {}", paymentId, e.getMessage());
            // 예외 계층이 준비되기 전까지 컨트롤러에서 최소한의 변환 처리
            return ApiResponse.error(e.getMessage());
        }
    }
}
