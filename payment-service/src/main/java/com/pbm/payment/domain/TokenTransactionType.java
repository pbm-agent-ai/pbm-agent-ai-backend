package com.pbm.payment.domain;

/**
 * 토큰 거래 유형.
 *
 * CHARGE: 마스터 지갑 → 사용자 지갑 충전
 * DEDUCT: 결제 실행으로 인한 토큰 차감
 * FEE:    온체인 트랜잭션 가스비(ETH→PBM 환산) 차감
 */
public enum TokenTransactionType {
    CHARGE,
    DEDUCT,
    FEE
}
