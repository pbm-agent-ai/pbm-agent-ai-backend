package com.pbm.price.exception;

/**
 * 다른 사용자의 모니터링 구독에 접근 시도할 때 발생하는 예외.
 *
 * 역할: subscriptionId는 존재하지만 요청 userId와 소유자가 다를 경우 403을 반환한다.
 * 연관: GlobalExceptionHandler, MonitoringSubscriptionService.
 */
public class SubscriptionAccessDeniedException extends RuntimeException {

    public SubscriptionAccessDeniedException(Long subscriptionId, Long userId) {
        super("해당 모니터링 구독에 대한 권한이 없습니다. subscriptionId=" + subscriptionId + ", userId=" + userId);
    }
}
