package com.pbm.command.exception;

/**
 * 브라우저 에이전트 토큰이 유효하지 않을 때 발생하는 예외.
 *
 * 역할: pairing/device/agent 토큰 검증 실패를 401 응답으로 변환하기 위해 사용한다.
 * 연관: BrowserAgentTokenUtil, GlobalExceptionHandler.
 */
public class InvalidBrowserAgentTokenException extends RuntimeException {

    public InvalidBrowserAgentTokenException(String message) {
        super(message);
    }
}
