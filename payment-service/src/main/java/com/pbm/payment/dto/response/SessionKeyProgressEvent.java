package com.pbm.payment.dto.response;

/**
 * 세션키 등록 진행 단계 SSE 이벤트 페이로드.
 *
 * @param step    단계 식별자 (예: WALLET_CHECK, BLOCKCHAIN_REGISTERED)
 * @param message 사람이 읽을 수 있는 단계 설명
 * @param detail  추가 정보 (txHash, 주소 등 — 없으면 null)
 */
public record SessionKeyProgressEvent(
        String step,
        String message,
        String detail
) {
    public static SessionKeyProgressEvent of(String step, String message) {
        return new SessionKeyProgressEvent(step, message, null);
    }

    public static SessionKeyProgressEvent of(String step, String message, String detail) {
        return new SessionKeyProgressEvent(step, message, detail);
    }
}
