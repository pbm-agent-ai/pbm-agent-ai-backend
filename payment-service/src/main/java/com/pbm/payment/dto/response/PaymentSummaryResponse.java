package com.pbm.payment.dto.response;

import com.pbm.payment.domain.Payment;
import com.pbm.payment.domain.PaymentStatus;

import java.time.Instant;

/**
 * 결제 목록 조회용 요약 응답 DTO.
 * 
 * 역할: 사용자별 결제 내역을 간략하게 표현하며, 불변 record로 데이터 무결성을 보장한다.
 * 연관: Payment 엔티티의 필드 중 목록 조회에 필요한 핵심 정보만 담는다.
 *
 * @param paymentId   결제 식별자 ("pay-" 형식)
 * @param userId      사용자 ID
 * @param productName 상품명
 * @param amount      결제 금액
 * @param currency    통화 코드 (KRW, USD 등)
 * @param status      결제 상태 (PENDING, SUCCESS, FAILED)
 * @param createdAt   결제 생성 시각
 */

public record PaymentSummaryResponse(
        String paymentId,
        Long userId,
        String productName,
        Integer amount,
        String currency,
        PaymentStatus status,
        Instant createdAt
) {
    /** Payment 엔티티를 요약 DTO로 변환한다. */
    // Payment는 DB에서 꺼낸 엔티티이다.
    // DB -> Payment 엔티티 -> from() -> DTO -> 클라이언트(Chrome Extension)
    public static PaymentSummaryResponse from(Payment payment) {
        return new PaymentSummaryResponse(
                payment.getPaymentId(),
                payment.getUserId(),
                payment.getProductName(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt()
        );
    }
}
