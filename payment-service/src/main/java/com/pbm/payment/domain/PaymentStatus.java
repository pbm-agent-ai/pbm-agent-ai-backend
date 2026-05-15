package com.pbm.payment.domain;

/**
 * 결제 상태 구분 enum.
 *
 * 역할: Payment 엔티티가 현재 어떤 결제 단계에 있는지 명확히 표현한다.
 * 동작: DB에는 문자열(EnumType.STRING)로 저장하여 사람이 읽기 쉽게 유지한다.
 * 연관: Payment.
 */
public enum PaymentStatus {
    /** 결제 대기: 아직 블록체인에 트랜잭션을 전송하지 않은 초기 상태 */
    PENDING,
    /** 결제 성공: 블록체인 트랜잭션이 정상적으로 완료된 상태 */
    SUCCESS,
    /** 결제 실패: 블록체인 오류, 잔액 부족 등으로 결제가 실패한 상태 */
    FAILED
}
