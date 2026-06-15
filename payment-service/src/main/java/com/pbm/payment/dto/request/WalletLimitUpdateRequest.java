package com.pbm.payment.dto.request;

/**
 * PBM 지갑 한도 수정 요청 DTO.
 *
 * @param walletLimitKrw 지갑 전체 PBM 한도 (KRW 기준, 1 이상)
 */
public record WalletLimitUpdateRequest(
        Long walletLimitKrw
) {
}
