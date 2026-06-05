package com.pbm.payment.domain;

/**
 * 세션키 상태 enum.
 * <p>
 * ACTIVE  : 블록체인에 등록되어 결제에 사용 가능한 상태.
 * EXPIRED : 유효 기간이 경과하거나 모니터링이 종료된 상태.
 * REVOKED : 사용자 또는 관리자가 수동으로 취소한 상태.
 */
public enum SessionKeyStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
