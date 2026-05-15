package com.pbm.payment.service;

/**
 * 결제 처리 결과를 담는 불변 DTO.
 *
 * 역할: PaymentProcessor.process() 호출 후 성공/실패 여부와 부가 정보를
 *       PaymentService가 분기 처리할 수 있도록 전달한다.
 * 동작: 정적 팩토리 메서드 success() / failure()로만 인스턴스를 생성한다.
 *       생성자는 private으로 두어 외부에서 직접 호출하지 못하게 막는다.
 * 연관: PaymentProcessor, PaymentService.
 */
public record PaymentProcessResult(
        boolean success,
        String transactionHash,
        String message,
        String failureReason
) {

    /**
     * 결제 성공 결과를 생성한다.
     *
     * @param transactionHash 블록체인 트랜잭션 해시 (null이면 자동으로 "0x" + 해시키워드가 채워진다)
     * @param message         성공 메시지 (예: "블록체인 결제가 정상적으로 완료되었습니다")
     * @return 성공 결과 인스턴스
     */
    public static PaymentProcessResult success(String transactionHash, String message) {
        return new PaymentProcessResult(true, transactionHash, message, null);
    }

    /**
     * 결제 실패 결과를 생성한다.
     *
     * @param message       실패 요약 메시지 (예: "블록체인 결제 처리 중 오류가 발생했습니다")
     * @param failureReason 상세 실패 사유 (예: "잔액 부족", "네트워크 오류")
     * @return 실패 결과 인스턴스
     */
    public static PaymentProcessResult failure(String message, String failureReason) {
        return new PaymentProcessResult(false, null, message, failureReason);
    }
}
