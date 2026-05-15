package com.pbm.payment.service;

import com.pbm.payment.domain.Payment;
import com.pbm.payment.dto.event.PaymentRequestEvent;
import com.pbm.payment.dto.event.PaymentRequestEventPayload;
import com.pbm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PBM 결제 처리를 총괄하는 서비스.
 *
 * 역할: Kafka에서 수신한 결제 요청 이벤트를 받아
 *       (1) 결제 엔티티 생성/저장 → (2) PaymentProcessor 호출 → (3) 결과에 따라 상태 갱신/저장
 *       → (4) PaymentResultEvent 발행의 흐름을 오케스트레이션한다.
 * 동작: 클래스 레벨 @Transactional(readOnly = true)로 기본은 읽기 전용,
 *       쓰기 작업 메서드에만 @Transactional을 별도로 붙여 변경 트랜잭션을 연다.
 *       결제 처리 완료 후 PaymentResultEventPublisher를 통해
 *       notification-service가 소비할 payment-result 이벤트를 발행한다.
 * 연관: PaymentRepository, PaymentProcessor, PaymentResultEventPublisher, PaymentRequestEvent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;
    private final PaymentResultEventPublisher paymentResultEventPublisher;

    /**
     * Kafka에서 수신한 결제 요청 이벤트를 처리한다.
     *
     * 처리 흐름:
     * 1. 이벤트의 payload 정보를 바탕으로 Payment 엔티티를 생성하고 DB에 저장한다.
     *    - paymentId는 이벤트의 eventId가 아닌 서비스 내부에서 "pay-" + UUID로 생성한다.
     * 2. PaymentProcessor.process()를 호출하여 실제 블록체인 결제를 수행한다.
     * 3. 결과에 따라 Payment.markSuccess() 또는 Payment.markFailed()를 호출하고
     *    변경된 엔티티를 다시 저장한다.
     * 4. 최종 상태 저장 후 PaymentResultEventPublisher를 통해
     *    payment-result 토픽으로 결제 결과 이벤트를 발행한다.
     *
     * @param event Kafka에서 수신한 결제 요청 이벤트
     */
    @Transactional
    public void processPaymentRequest(PaymentRequestEvent event) {
        PaymentRequestEventPayload payload = event.payload();

        // 1. 서비스 내부에서 고유한 결제 식별자를 생성한다.
        //    Kafka eventId를 재사용하지 않는 이유: 이벤트 재처리나 재발행 상황에서
        //    동일한 eventId로 중복 결제가 생성되는 것을 방지하기 위함이다.
        //    eventId는 kafka 메시지를 식별하는 용도이고, paymentId는 결제건을 식별하는 용도이다.
        String paymentId = generatePaymentId();

        // 2. 초기 상태(PENDING)로 결제 엔티티 생성 후 저장
        Payment payment = Payment.create(
                paymentId,
                payload.userId(),
                payload.productName(),
                payload.productUrl(),
                payload.amount(),
                payload.currency()
        );
        paymentRepository.save(payment);
        log.info("결제 엔티티 생성 완료: paymentId={}, userId={}, amount={} {}",
                paymentId, payload.userId(), payload.amount(), payload.currency());

        // 3. 블록체인 결제 처리 호출
        PaymentProcessResult result;
        try {
            result = paymentProcessor.process(payment);
        } catch (Exception e) {
            // PaymentProcessor 내부에서 예기치 못한 예외가 발생한 경우 결제 실패로 처리한다.
            log.error("결제 처리 중 예외 발생: paymentId={}, 원인={}", paymentId, e.getMessage(), e);
            payment.markFailed("결제 처리 중 서버 오류: " + e.getMessage());
            paymentRepository.save(payment);

            // 결제 실패 결과를 payment-result 토픽으로 발행
            paymentResultEventPublisher.publish(payment,
                    "결제 처리 중 오류가 발생했습니다: " + e.getMessage());
            return;
        }

        // 4. 결과에 따라 결제 상태 갱신
        if (result.success()) {
            payment.markSuccess(result.transactionHash());
            log.info("결제 성공: paymentId={}, txHash={}", paymentId, result.transactionHash());
        } else {
            payment.markFailed(result.failureReason());
            log.warn("결제 실패: paymentId={}, 사유={}", paymentId, result.failureReason());
        }

        // 5. 변경된 상태를 DB에 반영
        paymentRepository.save(payment);

        // 6. payment-result 토픽으로 결제 결과 이벤트 발행
        paymentResultEventPublisher.publish(payment, result.message());
    }

    /**
     * 특정 사용자의 결제 내역을 최신순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @return 사용자의 모든 결제 건 목록 (생성일 내림차순)
     */
    public List<Payment> getPaymentsByUserId(Long userId) {
        return paymentRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 결제 식별자(paymentId)로 결제 건을 단건 조회한다.
     *
     * @param paymentId 서비스 내부 결제 식별자 ("pay-" 로 시작하는 문자열)
     * @return 조회된 Payment 엔티티
     * @throws IllegalArgumentException 해당 paymentId를 가진 결제가 존재하지 않을 경우
     */
    public Payment getPaymentByPaymentId(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제 건을 찾을 수 없습니다: paymentId=" + paymentId));
    }

    /**
     * 서비스 내부에서 사용할 결제 식별자를 생성한다.
     *
     * 형식: "pay-" + UUID 앞 8자리
     * 예: pay-a1b2c3d4
     *
     * Kafka eventId와 별도로 관리하여 이벤트 재처리 시에도 중복 결제 생성을 방지한다.
     *
     * @return 생성된 결제 식별자
     */
    private String generatePaymentId() {
        return "pay-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
