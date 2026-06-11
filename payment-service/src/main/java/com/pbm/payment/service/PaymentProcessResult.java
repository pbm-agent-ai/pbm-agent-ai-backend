package com.pbm.payment.service;

/**
 * 결제 처리 결과를 담는 불변 DTO.
 *
 * 역할: PaymentProcessor.process() 호출 후 성공/실패 여부와 부가 정보를
 *       PaymentService가 분기 처리할 수 있도록 전달한다.
 * 동작: 정적 팩토리 메서드 success() / failure()로만 인스턴스를 생성한다.
 * 연관: PaymentProcessor, PaymentService.
 *
 * @param gasFeeKrw 결제 가스비 (KRW 단위 정수, 성공 시에만 값이 있음)
 */
public record PaymentProcessResult(
        boolean success,
        String transactionHash,
        String message,
        String failureReason,
        Integer gasFeeKrw
) {

    /**
     * 결제 성공 결과를 생성한다 (가스비 포함).
     *
     * @param transactionHash 블록체인 트랜잭션 해시
     * @param message         성공 메시지
     * @param gasFeeKrw       결제 가스비 (KRW 단위 정수)
     * @return 성공 결과 인스턴스
     */
    public static PaymentProcessResult success(String transactionHash, String message, Integer gasFeeKrw) {
        return new PaymentProcessResult(true, transactionHash, message, null, gasFeeKrw);
    }

    /**
     * 결제 성공 결과를 생성한다 (가스비 미포함 — 스텁용).
     *
     * @param transactionHash 블록체인 트랜잭션 해시
     * @param message         성공 메시지
     * @return 성공 결과 인스턴스
     */
    public static PaymentProcessResult success(String transactionHash, String message) {
        return new PaymentProcessResult(true, transactionHash, message, null, null);
    }

    /**
     * 결제 실패 결과를 생성한다.
     *
     * @param message       실패 요약 메시지
     * @param failureReason 상세 실패 사유
     * @return 실패 결과 인스턴스
     */
    public static PaymentProcessResult failure(String message, String failureReason) {
        return new PaymentProcessResult(false, null, message, failureReason, null);
    }
}
