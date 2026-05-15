package com.pbm.payment.dto.response;

import com.pbm.payment.domain.Payment;
import com.pbm.payment.domain.PaymentStatus;

import java.time.Instant;

/**
 * 결제 상세 조회용 응답 DTO.
 * 
 * 역할: 단건 결제의 모든 정보를 클라이언트에 전달하며, 불변 record로 데이터 무결성을 보장한다.
 * 연관: Payment 엔티티의 모든 조회 가능 필드를 포함한다.
 *
 * @param paymentId      결제 식별자 ("pay-" 형식)
 * @param userId         사용자 ID
 * @param productName    상품명
 * @param productUrl     상품 URL (없을 수 있음)
 * @param amount         결제 금액
 * @param currency       통화 코드 (KRW, USD 등)
 * @param status         결제 상태 (PENDING, SUCCESS, FAILED)
 * @param transactionHash 블록체인 트랜잭션 해시 (결제 성공 시에만 존재)
 * @param failureReason  결제 실패 사유 (결제 실패 시에만 존재)
 * @param createdAt      결제 생성 시각
 * @param updatedAt      마지막 갱신 시각
 */
public record PaymentDetailResponse(
        String paymentId,
        Long userId,
        String productName,
        String productUrl,
        Integer amount,
        String currency,
        PaymentStatus status,
        String transactionHash,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    /** Payment 엔티티를 상세 DTO로 변환한다. */
    public static PaymentDetailResponse from(Payment payment) {
        return new PaymentDetailResponse(
                payment.getPaymentId(),
                payment.getUserId(),
                payment.getProductName(),
                payment.getProductUrl(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getTransactionHash(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
