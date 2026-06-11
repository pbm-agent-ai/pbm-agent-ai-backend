package com.pbm.payment.dto.response;

import com.pbm.payment.domain.TokenTransaction;
import com.pbm.payment.domain.TokenTransactionStatus;
import com.pbm.payment.domain.TokenTransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 토큰 거래 내역 응답 DTO (충전 + 차감 통합).
 *
 * @param id            DB 거래 식별자
 * @param subscriptionId 모니터링 구독 ID (상품 단위 연결용, null 가능)
 * @param type          거래 유형 (CHARGE / DEDUCT)
 * @param amountPbm     거래 수량 (사람이 읽을 수 있는 단위)
 * @param amountWei     거래 수량 (wei 단위)
 * @param txHash        블록체인 트랜잭션 해시
 * @param status        거래 상태 (PENDING / SUCCESS / FAILED)
 * @param note          거래 메모
 * @param createdAt     거래 생성 시각
 */
public record TokenTransactionResponse(
        Long id,
        Long subscriptionId,
        TokenTransactionType type,
        BigDecimal amountPbm,
        String amountWei,
        String txHash,
        TokenTransactionStatus status,
        String note,
        Instant createdAt
) {
    private static final BigDecimal DECIMALS = BigDecimal.TEN.pow(18);

    public static TokenTransactionResponse from(TokenTransaction tx) {
        BigDecimal amountPbm = new BigDecimal(tx.getAmountWei())
                .divide(DECIMALS, 4, RoundingMode.DOWN);
        return new TokenTransactionResponse(
                tx.getId(),
                tx.getSubscriptionId(),
                tx.getType(),
                amountPbm,
                tx.getAmountWei().toString(),
                tx.getTxHash(),
                tx.getStatus(),
                tx.getNote(),
                tx.getCreatedAt()
        );
    }
}
