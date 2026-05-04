package com.pbm.price.exception;

/**
 * 외부 API 호출 실패 시 발생하는 커스텀 예외
 *
 * Circuit Breaker가 OPEN 상태이거나 재시도가 모두 실패한 경우 발생한다.
 * 스케줄러는 이미 개별 대상별로 예외를 격리하므로,
 * 빈 결과를 반환하기보다 명확한 예외를 전파하여 호출자가 장애 상황을 인지할 수 있도록 한다.
 */
public class ExternalApiException extends RuntimeException {

    /** 지정한 메시지로 ExternalApiException을 생성한다 */
    public ExternalApiException(String message) {
        super(message);
    }

    /** 지정한 메시지와 원인 예외로 ExternalApiException을 생성한다 */
    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
}