package com.pbm.payment.service;

import com.pbm.payment.domain.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * PaymentProcessor의 스텁(더미) 구현체.
 *
 * 역할: 아직 실제 블록체인 연동이 준비되지 않은 개발 초기 단계에서
 *       결제 흐름 전체를 검증할 수 있도록 항상 성공하는 가짜 프로세서를 제공한다.
 * 동작: process() 호출 시 항상 PaymentProcessResult.success()를 반환하며,
 *       트랜잭션 해시는 "0x" + UUID 기반의 가짜 값을 생성한다.
 *       추후 Web3j 연동 구현체로 교체할 예정이다.
 * 연관: PaymentProcessor, PaymentService.
 */
@Slf4j
@Component
public class StubPaymentProcessor implements PaymentProcessor {

    /**
     * 항상 결제 성공 결과를 반환하는 스텁 처리.
     *
     * 실제 블록체인 연동 전까지 모든 결제 요청을 성공으로 처리하여
     * payment-service의 내부 플로우(생성 → 처리 → 상태 갱신 → 저장)를
     * 끝까지 검증할 수 있게 한다.
     *
     * @param payment 처리할 결제 엔티티
     * @return 가짜 트랜잭션 해시를 포함한 성공 결과
     */
    @Override
    public PaymentProcessResult process(Payment payment) {
        // UUID 기반의 가짜 트랜잭션 해시 생성. 실제 Web3j 연동 시 web3j.ethSendTransaction() 결과로 대체한다.
        String fakeTransactionHash = "0x" + UUID.randomUUID().toString    ().replace("-", "");

        log.info("스텁 결제 처리 완료: paymentId={}, userId={}, amount={}, fakeTxHash={}",
                payment.getPaymentId(), payment.getUserId(), payment.getAmount(), fakeTransactionHash);

        return PaymentProcessResult.success(
                fakeTransactionHash,
                "스텁: 블록체인 결제가 정상적으로 처리되었습니다 (개발 환경)"
        );
    }
}
