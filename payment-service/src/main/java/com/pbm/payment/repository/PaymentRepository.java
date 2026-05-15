package com.pbm.payment.repository;

import com.pbm.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Payment 엔티티 조회/저장 레포지토리.
 *
 * 역할: 결제 건의 CRUD와 조회 쿼리를 제공한다.
 * 동작: paymentId로 단건 조회하거나, 사용자별 최신 결제 내역을 조회할 수 있다.
 * 연관: Payment.
 */
/*Service가 DB에서 데이터를 꺼내거나 저장할 때 무조건 여기를 통과한다.
  PaymentService -> PaymentRepository -> DB
  JpaRepository를 상속받으면 기본 CRUD가 공짜로 딸려온다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 외부 결제 식별자(paymentId)로 결제 건을 단건 조회한다.
     *
     * Kafka 이벤트 수신 시 이미 처리된 결제인지 중복 확인 용도로 사용한다.
     *
     * @param paymentId 외부 결제 식별자
     * @return 해당 paymentId를 가진 Payment (존재하지 않으면 empty)
     */
    // JPA 핵심 기능인데, 메서드 이름만 지키면 SQL을 직접 안 써도 된다.
    // Optional은 조회 결과가 없을 수도 있을 떄 쓰는 컨테이너이다.
    Optional<Payment> findByPaymentId(String paymentId);

    /**
     * 특정 사용자의 결제 내역을 최신순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @return 사용자의 결제 목록 (생성일 내림차순)
     */
    List<Payment> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
