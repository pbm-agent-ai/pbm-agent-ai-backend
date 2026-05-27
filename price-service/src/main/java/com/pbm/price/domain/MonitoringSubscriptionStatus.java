package com.pbm.price.domain;

/**
 * 모니터링 구독 상태 enum.
 *
 * 역할: monitoring_subscription 건별 모니터링 진행 상태를 구분한다.
 * 동작: DB에는 문자열(EnumType.STRING)로 저장하여 사람이 읽기 쉽게 유지한다.
 * 연관: MonitoringSubscription.
 */
public enum MonitoringSubscriptionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    /** 목표 가격 충족으로 트리거되어 결제/알림이 발행된 상태 */
    TRIGGERED,
    /** 사용자가 직접 모니터링을 중단한 상태 */
    CANCELLED
}
