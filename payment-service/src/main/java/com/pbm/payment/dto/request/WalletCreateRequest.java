package com.pbm.payment.dto.request;

/**
 * PBM 스마트 지갑 생성 요청 DTO.
 *
 * @param walletLimitKrw 지갑 전체 PBM 한도 (KRW 기준, 1 이상)
 */
public record WalletCreateRequest(
        Long walletLimitKrw
) {
}
