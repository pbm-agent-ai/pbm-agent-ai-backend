package com.pbm.payment.controller;

import com.pbm.payment.common.ApiResponse;
import com.pbm.payment.domain.Payment;
import com.pbm.payment.domain.TokenTransaction;
import com.pbm.payment.domain.TokenTransactionType;
import com.pbm.payment.dto.response.PaymentDetailResponse;
import com.pbm.payment.dto.response.PaymentSummaryResponse;
import com.pbm.payment.repository.TokenTransactionRepository;
import com.pbm.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PBM 결제 내역 조회 API 컨트롤러.
 *
 * 역할: 사용자별 결제 목록 조회와 결제 건 단건 상세 조회를 제공한다.
 * 동작: gateway의 /api/v1/payments/** 경로로 라우팅되며,
 *       모든 응답은 ApiResponse<T> 래퍼로 감싸 반환한다.
 * 연관: PaymentService, PaymentSummaryResponse, PaymentDetailResponse, TokenTransactionRepository.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final TokenTransactionRepository tokenTransactionRepository;

    /**
     * 현재 인증된 사용자의 결제 내역 목록을 최신순으로 조회한다.
     * Gateway가 JWT를 검증하고 X-User-Id 헤더로 userId를 주입한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id 헤더)
     * @return 사용자의 결제 요약 목록을 감싼 ApiResponse
     */
    /** PBM 토큰 소수점: 18자리 */
    private static final BigInteger TOKEN_DECIMALS = BigInteger.TEN.pow(18);

    @GetMapping
    public ApiResponse<List<PaymentSummaryResponse>> getPaymentsByUserId(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("결제 목록 조회 요청 - userId: {}", userId);

        List<Payment> paymentList = paymentService.getPaymentsByUserId(userId);

        // 사용자의 FEE 트랜잭션을 일괄 조회 후 subscriptionId별 합계 계산
        List<TokenTransaction> feeTransactions = tokenTransactionRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, TokenTransactionType.FEE);

        Map<Long, Integer> feeBySubscription = feeTransactions.stream()
                .filter(tx -> tx.getSubscriptionId() != null)
                .collect(Collectors.groupingBy(
                        TokenTransaction::getSubscriptionId,
                        Collectors.reducing(BigInteger.ZERO,
                                TokenTransaction::getAmountWei,
                                BigInteger::add)
                )).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().divide(TOKEN_DECIMALS).intValue()
                ));

        List<PaymentSummaryResponse> payments = paymentList.stream()
                .map(payment -> {
                    // FEE 트랜잭션 합계 우선, 없으면 Payment에 저장된 gasFeeKrw로 fallback
                    Integer feeAmountKrw = payment.getSubscriptionId() != null
                            ? feeBySubscription.get(payment.getSubscriptionId())
                            : null;
                    if (feeAmountKrw == null) {
                        feeAmountKrw = payment.getGasFeeKrw();
                    }
                    return PaymentSummaryResponse.from(payment, feeAmountKrw);
                })
                .toList();

        return ApiResponse.success(payments, "결제 목록 조회 성공");
    }

    /**
     * 결제 식별자(paymentId)로 결제 건의 상세 정보를 조회한다.
     * 해당 사용자의 FEE 타입 거래 내역(결제 가스비 + 세션키 등록 수수료)을 함께 반환한다.
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
            Payment payment = paymentService.getPaymentByPaymentId(paymentId);

            // 해당 사용자의 FEE 타입 거래 내역 조회 (상품/구독 단위로 묶을 수 있으면 subscriptionId 우선 사용)
            List<TokenTransaction> feeTransactions = payment.getSubscriptionId() != null
                    ? tokenTransactionRepository.findByUserIdAndSubscriptionIdAndTypeOrderByCreatedAtDesc(
                            payment.getUserId(), payment.getSubscriptionId(), TokenTransactionType.FEE)
                    : tokenTransactionRepository.findByUserIdAndTypeOrderByCreatedAtDesc(
                            payment.getUserId(), TokenTransactionType.FEE);

            PaymentDetailResponse response = PaymentDetailResponse.from(payment, feeTransactions);
            return ApiResponse.success(response, "결제 상세 조회 성공");
        } catch (IllegalArgumentException e) {
            log.warn("결제 건 조회 실패 - paymentId: {}, 원인: {}", paymentId, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }
}
