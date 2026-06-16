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
 * @param paymentId       결제 식별자 ("pay-" 형식)
 * @param userId          사용자 ID
 * @param productName     상품명
 * @param productImageUrl 상품 이미지 URL (null 가능)
 * @param amount          결제 금액
 * @param currency        통화 코드 (KRW, USD 등)
 * @param status          결제 상태 (PENDING, SUCCESS, FAILED)
 * @param gasFeeKrw       결제 가스비 (KRW 단위 정수, 성공 시에만 값이 있음)
 * @param feeAmountKrw    총 수수료 합계 (세션키 등록 + 결제 가스비 등, null 가능)
 * @param failureReason   결제 실패 사유 (FAILED 상태일 때만 값이 있음, 나머지는 null)
 * @param createdAt       결제 생성 시각
 */
public record PaymentSummaryResponse(
        String paymentId,
        Long userId,
        String productName,
        String productImageUrl,
        Integer amount,
        String currency,
        PaymentStatus status,
        Integer gasFeeKrw,
        Integer feeAmountKrw,
        String failureReason,
        Instant createdAt
) {
    /** Payment 엔티티를 요약 DTO로 변환한다 (수수료 합계 없이). */
    public static PaymentSummaryResponse from(Payment payment) {
        return from(payment, null);
    }

    /** Payment 엔티티를 요약 DTO로 변환한다 (수수료 합계 포함). */
    public static PaymentSummaryResponse from(Payment payment, Integer feeAmountKrw) {
        return new PaymentSummaryResponse(
                payment.getPaymentId(),
                payment.getUserId(),
                payment.getProductName(),
                payment.getProductImageUrl(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getGasFeeKrw(),
                feeAmountKrw,
                payment.getFailureReason(),
                payment.getCreatedAt()
        );
    }
}
