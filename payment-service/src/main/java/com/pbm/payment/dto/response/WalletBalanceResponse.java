package com.pbm.payment.dto.response;

import java.math.BigDecimal;

/**
 * PBM 스마트 지갑 잔액 응답 DTO.
 *
 * @param walletAddress PBMSmartAccount 컨트랙트 주소
 * @param pbmBalance    PBM 토큰 잔액 (사람이 읽을 수 있는 단위, 소수점 4자리)
 * @param pbmBalanceWei PBM 토큰 잔액 (wei 단위, 18 decimals)
 */
public record WalletBalanceResponse(
        String walletAddress,
        BigDecimal pbmBalance,
        String pbmBalanceWei
) {
}
