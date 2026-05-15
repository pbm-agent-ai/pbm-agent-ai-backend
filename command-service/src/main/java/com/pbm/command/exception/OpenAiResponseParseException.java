package com.pbm.command.exception;

/**
 * OpenAI 응답 JSON 파싱 실패 예외.
 *
 * 역할: 모델이 반환한 content를 DTO로 변환하지 못했을 때 명확한 원인을 구분한다.
 * 동작: malformed JSON, 스키마 불일치, content 누락 시 이 예외를 발생시킨다.
 * 연관: OpenAiCommandClient.
 */
public class OpenAiResponseParseException extends RuntimeException {

    public OpenAiResponseParseException(String message) {
        super(message);
    }

    public OpenAiResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
