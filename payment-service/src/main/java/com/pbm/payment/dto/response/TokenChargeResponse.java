package com.pbm.payment.dto.response;

import com.pbm.payment.domain.TokenTransaction;
import com.pbm.payment.domain.TokenTransactionStatus;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * PBM 토큰 충전 결과 응답 DTO.
 *
 * @param transactionId DB 토큰 거래 식별자
 * @param walletAddress 충전된 스마트 지갑 주소
 * @param amountPbm     충전 수량 (사람이 읽을 수 있는 단위)
 * @param amountWei     충전 수량 (wei 단위)
 * @param txHash        블록체인 트랜잭션 해시 (성공 시)
 * @param status        거래 상태 (SUCCESS / FAILED)
 * @param createdAt     거래 생성 시각
 */
public record TokenChargeResponse(
        Long transactionId,
        String walletAddress,
        BigDecimal amountPbm,
        String amountWei,
        String txHash,
        TokenTransactionStatus status,
        Instant createdAt
) {
    private static final BigDecimal DECIMALS = BigDecimal.TEN.pow(18);

    public static TokenChargeResponse from(TokenTransaction tx) {
        BigDecimal amountPbm = new BigDecimal(tx.getAmountWei())
                .divide(DECIMALS, 4, RoundingMode.DOWN);
        return new TokenChargeResponse(
                tx.getId(),
                tx.getWalletAddress(),
                amountPbm,
                tx.getAmountWei().toString(),
                tx.getTxHash(),
                tx.getStatus(),
                tx.getCreatedAt()
        );
    }
}
