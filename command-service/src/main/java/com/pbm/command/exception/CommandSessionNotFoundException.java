package com.pbm.command.exception;

/**
 * 명령 세션을 찾을 수 없을 때 발생하는 예외.
 *
 * 역할: commandId로 CommandSession을 조회했으나 DB에 존재하지 않을 때 던진다.
 * 동작: GlobalExceptionHandler가 HTTP 404(Not Found)로 변환하여 응답한다.
 * 연관: CommandSessionService, GlobalExceptionHandler.
 */
public class CommandSessionNotFoundException extends RuntimeException {

    public CommandSessionNotFoundException(String message) {
        super(message);
    }

    public CommandSessionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
