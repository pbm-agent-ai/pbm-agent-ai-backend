package com.pbm.command.exception;

/**
 * external-api-service 프록시 호출 실패 예외.
 *
 * 역할: command-service가 external-api-service에 의존하는 구간에서 네트워크 오류,
 *       Circuit Breaker OPEN, 비정상 응답 등을 하나의 예외 타입으로 감싼다.
 * 동작: OpenAiCommandClient의 fallback 또는 프록시 호출 실패 지점에서 발생하며,
 *       GlobalExceptionHandler가 HTTP 503으로 변환한다.
 * 연관: OpenAiCommandClient, GlobalExceptionHandler.
 */
public class ExternalApiProxyException extends RuntimeException {

    public ExternalApiProxyException(String message) {
        super(message);
    }

    public ExternalApiProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
