package com.pbm.payment.domain;

/**
 * 토큰 거래 상태.
 *
 * PENDING: 블록체인 트랜잭션 전송 전
 * SUCCESS: 온체인 확정 완료
 * FAILED:  트랜잭션 실패
 */
public enum TokenTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED
}
