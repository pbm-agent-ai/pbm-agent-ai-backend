package com.pbm.payment.dto.request;

/**
 * PBM 토큰 충전 요청 DTO.
 *
 * @param amountPbm 충전할 PBM 토큰 수량 (사람이 읽을 수 있는 단위, 소수점 불가)
 */
public record TokenChargeRequest(
        Long amountPbm
) {
}
