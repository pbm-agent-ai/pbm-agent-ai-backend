package com.pbm.command.exception;

/**
 * OpenAI API 호출 실패 예외.
 *
 * 역할: 네트워크 오류, 모델 refusal, 비정상 finish reason 등 OpenAI 호출 자체의 실패를 감싼다.
 * 동작: RestClient 오류나 비정상 응답을 받으면 이 예외를 던져 상위 계층이 처리하게 한다.
 * 연관: OpenAiCommandClient.
 */
public class OpenAiClientException extends RuntimeException {

    public OpenAiClientException(String message) {
        super(message);
    }

    public OpenAiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
