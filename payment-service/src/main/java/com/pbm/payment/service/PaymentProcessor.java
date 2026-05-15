package com.pbm.payment.service;

import com.pbm.payment.domain.Payment;

/**
 * 블록체인 결제 처리를 추상화하는 인터페이스.
 *
 * 역할: PaymentService가 결제 실행 로직을 구현체에 의존하지 않고
 *       호출할 수 있도록 계약을 정의한다.
 * 동작: 구현체는 Payment 엔티티 정보를 바탕으로 실제 블록체인 트랜잭션을
 *       전송하고, 그 결과를 PaymentProcessResult로 반환한다.
 *       현재는 StubPaymentProcessor가 항상 성공하는 더미 구현을 제공한다.
 * 연관: PaymentService, StubPaymentProcessor.
 */
public interface PaymentProcessor {

    /**
     * 주어진 결제 건을 블록체인에서 처리한다.
     *
     * @param payment 처리할 결제 엔티티 (status=PENDING 상태여야 함)
     * @return 블록체인 처리 결과 (성공이면 transactionHash 포함, 실패면 failureReason 포함)
     */
    PaymentProcessResult process(Payment payment);
}
