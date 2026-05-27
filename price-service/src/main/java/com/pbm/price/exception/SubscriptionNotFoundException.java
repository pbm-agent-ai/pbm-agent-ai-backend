package com.pbm.price.exception;

/**
 * 모니터링 구독을 찾을 수 없을 때 발생하는 예외.
 *
 * 역할: subscriptionId로 조회했지만 존재하지 않는 구독 접근 시 404를 반환하기 위해 사용한다.
 * 연관: GlobalExceptionHandler, MonitoringSubscriptionService.
 */
public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(Long subscriptionId) {
        super("모니터링 구독을 찾을 수 없습니다. subscriptionId=" + subscriptionId);
    }
}
