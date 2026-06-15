package com.pbm.payment.dto.response;

/**
 * 토큰 충전 진행 단계 SSE 이벤트 페이로드.
 *
 * @param step    단계 식별자 (예: TX_SENT, GAS_CALCULATED)
 * @param message 사람이 읽을 수 있는 단계 설명
 * @param detail  추가 정보 (txHash, blockNumber, 금액 등 — 없으면 null)
 */
public record ChargeProgressEvent(
        String step,
        String message,
        String detail
) {
    public static ChargeProgressEvent of(String step, String message) {
        return new ChargeProgressEvent(step, message, null);
    }

    public static ChargeProgressEvent of(String step, String message, String detail) {
        return new ChargeProgressEvent(step, message, detail);
    }
}
