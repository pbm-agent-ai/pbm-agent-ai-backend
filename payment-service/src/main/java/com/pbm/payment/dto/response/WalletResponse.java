package com.pbm.payment.dto.response;

import com.pbm.payment.domain.UserWallet;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PBM 스마트 지갑 응답 DTO.
 *
 * @param id            DB 식별자
 * @param userId        사용자 식별자
 * @param walletAddress PBMSmartAccount 컨트랙트 주소
 * @param walletLimit   지갑 PBM 한도 (KRW 기준)
 * @param createdAt     생성 일시
 */
public record WalletResponse(
        Long id,
        Long userId,
        String walletAddress,
        BigDecimal walletLimit,
        Instant createdAt
) {
    /**
     * UserWallet 엔티티로부터 응답 DTO를 생성한다.
     *
     * @param wallet UserWallet 엔티티
     * @return WalletResponse
     */
    public static WalletResponse from(UserWallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getWalletAddress(),
                wallet.getWalletLimit(),
                wallet.getCreatedAt()
        );
    }
}
