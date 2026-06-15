package com.pbm.payment.dto.response;

import com.pbm.payment.domain.TokenTransaction;

import java.math.BigInteger;
import java.time.Instant;

/**
 * 수수료 상세 응답 DTO.
 *
 * 역할: 결제 상세 조회 시 관련 가스비 수수료 내역(세션키 등록, 결제 가스비 등)을
 *       사용자에게 표시하기 위한 불변 record.
 * 연관: TokenTransaction(FEE 타입), PaymentDetailResponse.
 *
 * @param subscriptionId 모니터링 구독 ID (상품 단위 연결용, null 가능)
 * @param type       수수료 유형 설명 (예: "결제 가스비", "세션키 등록 가스비")
 * @param amountKrw  수수료 금액 (KRW 단위 정수)
 * @param txHash     관련 트랜잭션 해시
 * @param createdAt  기록 시각
 */
public record FeeDetailResponse(
        Long subscriptionId,
        String type,
        Integer amountKrw,
        String txHash,
        Instant createdAt
) {
    /** PBM 토큰 소수점: 18자리 */
    private static final BigInteger TOKEN_DECIMALS = BigInteger.TEN.pow(18);

    /**
     * TokenTransaction(FEE 타입)을 FeeDetailResponse로 변환한다.
     * note에서 수수료 유형을 추출한다.
     */
    public static FeeDetailResponse from(TokenTransaction tx) {
        // note 형식: "가스비 차감 (결제 가스비) - 원인 tx: 0x..."
        // 괄호 안의 operationType을 추출
        String type = extractOperationType(tx.getNote());
        Integer amountKrw = tx.getAmountWei().divide(TOKEN_DECIMALS).intValue();

        return new FeeDetailResponse(tx.getSubscriptionId(), type, amountKrw, tx.getTxHash(), tx.getCreatedAt());
    }

    /**
     * note 문자열에서 수수료 유형을 추출한다.
     * 예: "가스비 차감 (세션키 등록) - 원인 tx: 0x..." → "세션키 등록"
     */
    private static String extractOperationType(String note) {
        if (note == null) return "가스비";
        int start = note.indexOf('(');
        int end = note.indexOf(')');
        if (start >= 0 && end > start) {
            return note.substring(start + 1, end);
        }
        return "가스비";
    }
}
