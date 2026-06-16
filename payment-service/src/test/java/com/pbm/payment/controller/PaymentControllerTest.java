package com.pbm.payment.controller;

import com.pbm.payment.domain.Payment;
import com.pbm.payment.domain.TokenTransactionType;
import com.pbm.payment.repository.TokenTransactionRepository;
import com.pbm.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentController 웹 레이어 테스트.
 * MockMvc를 사용하여 HTTP 요청/응답 계약을 검증한다.
 *
 * 검증 대상:
 * - 결제 목록 조회: 200 OK + ApiResponse<List<PaymentSummaryResponse>> JSON 형식
 * - 결제 상세 조회: 200 OK + ApiResponse<PaymentDetailResponse> JSON 형식
 * - 존재하지 않는 결제 조회: 200 OK + ApiResponse.error() JSON 형식
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** PaymentService를 Mock으로 대체하여 비즈니스 로직을 격리한다 */
    @MockBean
    private PaymentService paymentService;

    /** TokenTransactionRepository를 Mock으로 대체하여 FEE 내역 조회를 격리한다 */
    @MockBean
    private TokenTransactionRepository tokenTransactionRepository;

    // ──────────────── 테스트 픽스처 헬퍼 ────────────────

    /** 테스트용 Payment 엔티티 생성 (SUCCESS 상태) */
    private Payment createTestPayment(String paymentId, Long userId, String productName) {
        Payment payment = Payment.create(paymentId, userId, 123L, productName,
                "https://example.com/product/" + paymentId,
                15000, "KRW", null, "https://example.com/img/" + paymentId + ".jpg");
        payment.markSuccess("0xtxhash" + paymentId);
        return payment;
    }

    /** 테스트용 Payment 엔티티 생성 (FAILED 상태) */
    private Payment createFailedPayment(String paymentId, Long userId, String productName) {
        Payment payment = Payment.create(paymentId, userId, 456L, productName,
                "https://example.com/product/" + paymentId,
                8900, "USD", null, null);
        payment.markFailed("잔액 부족");
        return payment;
    }

    // ──────────────── 결제 목록 조회 테스트 ────────────────

    @Test
    @DisplayName("GET /api/v1/payments: 정상 조회 시 200 OK와 ApiResponse<List> JSON 반환")
    void getPaymentsByUserId_success_returns200WithApiResponse() throws Exception {
        // given - 사용자 1의 결제 2건이 존재하는 상황
        Payment pay1 = createTestPayment("pay-aa111111", 1L, "에어팟 프로 2세대");
        Payment pay2 = createFailedPayment("pay-bb222222", 1L, "아이폰 케이스");

        List<Payment> mockPayments = List.of(pay1, pay2);
        when(paymentService.getPaymentsByUserId(1L)).thenReturn(mockPayments);
        when(tokenTransactionRepository.findByUserIdAndTypeOrderByCreatedAtDesc(1L, TokenTransactionType.FEE))
                .thenReturn(List.of());

        // when & then - HTTP 상태코드와 ApiResponse JSON 형식 검증
        mockMvc.perform(get("/api/v1/payments")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                // ApiResponse 래퍼 구조 검증
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("결제 목록 조회 성공"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 첫 번째 결제 건 검증 (SUCCESS 상태)
                .andExpect(jsonPath("$.data[0].paymentId").value("pay-aa111111"))
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[0].productName").value("에어팟 프로 2세대"))
                .andExpect(jsonPath("$.data[0].productImageUrl").value("https://example.com/img/pay-aa111111.jpg"))
                .andExpect(jsonPath("$.data[0].amount").value(15000))
                .andExpect(jsonPath("$.data[0].currency").value("KRW"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                // 두 번째 결제 건 검증 (FAILED 상태)
                .andExpect(jsonPath("$.data[1].paymentId").value("pay-bb222222"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].currency").value("USD"))
                .andExpect(jsonPath("$.data[1].productImageUrl").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/payments: 결제 내역이 없는 사용자 조회 시 빈 배열 반환")
    void getPaymentsByUserId_emptyList_returns200WithEmptyArray() throws Exception {
        // given - 결제 내역이 없는 사용자
        when(paymentService.getPaymentsByUserId(99L)).thenReturn(List.of());
        when(tokenTransactionRepository.findByUserIdAndTypeOrderByCreatedAtDesc(99L, TokenTransactionType.FEE))
                .thenReturn(List.of());

        // when & then - 빈 배열이 정상 반환되는지 검증
        mockMvc.perform(get("/api/v1/payments")
                        .header("X-User-Id", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ──────────────── 결제 상세 조회 테스트 ────────────────

    @Test
    @DisplayName("GET /api/v1/payments/{paymentId}: 정상 상세 조회 시 200 OK와 ApiResponse<PaymentDetailResponse> JSON 반환")
    void getPaymentByPaymentId_success_returns200WithApiResponse() throws Exception {
        // given - SUCCESS 상태의 결제 건이 존재하는 상황
        Payment payment = createTestPayment("pay-cc333333", 2L, "갤럭시 버즈");
        when(paymentService.getPaymentByPaymentId("pay-cc333333")).thenReturn(payment);
        when(tokenTransactionRepository.findByUserIdAndSubscriptionIdAndTypeOrderByCreatedAtDesc(
                2L, 123L, TokenTransactionType.FEE))
                .thenReturn(List.of());

        // when & then - 상세 응답의 모든 필드 검증
        mockMvc.perform(get("/api/v1/payments/pay-cc333333"))
                .andExpect(status().isOk())
                // ApiResponse 래퍼 구조 검증
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("결제 상세 조회 성공"))
                .andExpect(jsonPath("$.data.paymentId").value("pay-cc333333"))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.productName").value("갤럭시 버즈"))
                .andExpect(jsonPath("$.data.productUrl").value("https://example.com/product/pay-cc333333"))
                .andExpect(jsonPath("$.data.amount").value(15000))
                .andExpect(jsonPath("$.data.currency").value("KRW"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.transactionHash").value("0xtxhashpay-cc333333"))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.feeDetails").isArray())
                .andExpect(jsonPath("$.data.feeDetails.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{paymentId}: 존재하지 않는 결제 조회 시 200 OK와 ApiResponse.error JSON 반환")
    void getPaymentByPaymentId_notFound_returns200WithErrorJson() throws Exception {
        // given - 해당 paymentId를 가진 결제가 존재하지 않는 상황
        String nonExistentId = "pay-notfound";
        when(paymentService.getPaymentByPaymentId(nonExistentId))
                .thenThrow(new IllegalArgumentException("결제 건을 찾을 수 없습니다: paymentId=" + nonExistentId));

        // when & then - ApiResponse.error JSON 형식 검증
        mockMvc.perform(get("/api/v1/payments/{paymentId}", nonExistentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        "결제 건을 찾을 수 없습니다: paymentId=" + nonExistentId));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{paymentId}: FAILED 상태 결제의 상세 조회 시 실패 사유 포함")
    void getPaymentByPaymentId_failedPayment_returnsFailureReason() throws Exception {
        // given - FAILED 상태의 결제 건이 존재하는 상황
        Payment failedPayment = createFailedPayment("pay-dd444444", 3L, "맥북 케이스");
        when(paymentService.getPaymentByPaymentId("pay-dd444444")).thenReturn(failedPayment);
        when(tokenTransactionRepository.findByUserIdAndSubscriptionIdAndTypeOrderByCreatedAtDesc(
                3L, 456L, TokenTransactionType.FEE))
                .thenReturn(List.of());

        // when & then - fail 상태 필드 검증
        mockMvc.perform(get("/api/v1/payments/pay-dd444444"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("잔액 부족"))
                .andExpect(jsonPath("$.data.transactionHash").doesNotExist());
    }
}
