package com.pbm.command.exception;

/**
 * AgentRun 생성/전이 시 충돌이 발생할 때 사용하는 예외.
 */
public class AgentRunConflictException extends RuntimeException {

    public AgentRunConflictException(String message) {
        super(message);
    }
}
